/*
 * Ister epub reader.
 *
 * Loaded as /reader/index.html?mediaFileId=<id>&bookId=<id>&token=<streamToken>[&title=...]
 *                              [&chapter=<0-based index>][&readAloud=1]
 *
 * Without a chapter the book opens at the saved reading position; with one it opens at that
 * chapter (matched against the TOC, falling back to the spine). readAloud=1 starts the media
 * overlay playback as soon as the section is displayed.
 *
 * The epub is never downloaded as a whole: epub.js is pointed at the server's
 * /epub/{mediaFileId}/resource/ base, so container.xml, the OPF and every chapter, image and
 * stylesheet are fetched lazily, one file at a time. Authentication uses the stream token, stored
 * in a same-origin cookie so the browser attaches it to every request (including images the
 * chapter iframes load themselves).
 *
 * Read-aloud (EPUB 3 media overlays) is implemented here: the OPF manifest points each chapter at
 * a SMIL file mapping text fragments to audio clips (clipBegin/clipEnd). One <audio> element plays
 * the chapter audio via the same resource endpoint (with Range support); on timeupdate the active
 * fragment is highlighted inside the rendition iframe and the page turns when it moves out of
 * view. Tapping a sentence seeks the audio to that sentence.
 */
(function () {
    "use strict";

    const params = new URLSearchParams(window.location.search);
    const mediaFileId = params.get("mediaFileId");
    const bookId = params.get("bookId");
    const token = params.get("token");
    const titleParam = params.get("title");
    const chapterParam = params.get("chapter");
    const chapterIndex = chapterParam !== null && chapterParam !== "" ? parseInt(chapterParam, 10) : null;
    const autoReadAloud = params.get("readAloud") === "1";

    // The reader is served from <base>/reader/, the API lives at <base>.
    const apiBase = window.location.pathname.replace(/\/reader\/.*$/, "");
    const resourceBase = `${apiBase}/epub/${mediaFileId}/resource/`;

    const viewerEl = document.getElementById("viewer");
    const titleEl = document.getElementById("title");
    const progressEl = document.getElementById("progressLabel");
    const readAloudButton = document.getElementById("readAloudButton");
    const messageEl = document.getElementById("message");

    if (!mediaFileId || !bookId) {
        showMessage("Missing mediaFileId/bookId in the URL.");
        return;
    }
    if (token) {
        // Same-origin cookie so chapter subresources authenticate too. Session-scoped: the
        // stream token itself is short-lived.
        document.cookie = `IsterStreamToken=${encodeURIComponent(token)}; path=${apiBase || "/"}; SameSite=Lax`;
    }
    if (titleParam) {
        titleEl.textContent = titleParam;
        document.title = titleParam;
    }

    function showMessage(text) {
        messageEl.textContent = text;
        messageEl.classList.remove("hidden");
    }

    function hideMessage() {
        messageEl.classList.add("hidden");
    }

    async function api(path, options) {
        const response = await fetch(apiBase + path, Object.assign({credentials: "same-origin"}, options));
        if (!response.ok) {
            throw new Error(`${path} failed: ${response.status}`);
        }
        return response.json();
    }

    /* ------------------------------------------------------------------ */
    /* Book setup                                                          */
    /* ------------------------------------------------------------------ */

    showMessage("Loading book…");

    const book = ePub(resourceBase);
    const rendition = book.renderTo(viewerEl, {
        width: "100%",
        height: "100%",
        flow: "paginated",
        spread: "auto",
        allowScriptedContent: false
    });

    rendition.themes.default({
        "body": {"color": "var(--fg) !important", "background": "transparent !important"},
        ".-epub-media-overlay-active": {"background-color": "rgba(255, 213, 79, 0.55) !important", "border-radius": "3px"}
    });

    let locationsReady = false;
    let suppressProgressSync = true;
    // Resolves once the media overlays have been detected (see the bottom of this file).
    let overlayReady;

    book.loaded.metadata.then(function (metadata) {
        if (!titleParam && metadata.title) {
            titleEl.textContent = metadata.title;
            document.title = metadata.title;
        }
    });

    /** The chapter's spine href, matched against the TOC and falling back to the spine order. */
    async function chapterHref(index) {
        try {
            const navigation = await book.loaded.navigation;
            const toc = (navigation && navigation.toc) || [];
            if (index < toc.length && toc[index].href) {
                return toc[index].href;
            }
            const section = book.spine.get(index);
            return section ? section.href : null;
        } catch (error) {
            console.warn("Could not resolve chapter " + index, error);
            return null;
        }
    }

    /** A requested chapter wins over the saved position; otherwise resume where the user left off. */
    async function startLocation() {
        if (chapterIndex !== null && !isNaN(chapterIndex) && chapterIndex >= 0) {
            const href = await chapterHref(chapterIndex);
            if (href) {
                return href;
            }
        }
        const saved = await api(`/reading-progress?bookId=${bookId}`).catch(() => null);
        return saved && saved.location ? saved.location : undefined;
    }

    Promise.resolve()
        .then(startLocation)
        .then(location => rendition.display(location))
        .then(function () {
            hideMessage();
            suppressProgressSync = false;
            if (autoReadAloud) {
                overlayReady.then(function () {
                    if (overlay.available && !overlay.playing) {
                        overlay.toggle();
                    }
                });
            }
            return book.locations.generate(1000);
        })
        .then(function () {
            locationsReady = true;
            updateProgressLabel(rendition.currentLocation());
        })
        .catch(function (error) {
            console.error(error);
            showMessage("Could not load the book.");
        });

    /* ------------------------------------------------------------------ */
    /* Navigation                                                          */
    /* ------------------------------------------------------------------ */

    document.getElementById("prevZone").addEventListener("click", () => rendition.prev());
    document.getElementById("nextZone").addEventListener("click", () => rendition.next());
    document.getElementById("backButton").addEventListener("click", function () {
        if (window.history.length > 1) {
            window.history.back();
        } else {
            window.close();
        }
    });

    function onKey(event) {
        if (event.key === "ArrowLeft") rendition.prev();
        if (event.key === "ArrowRight") rendition.next();
        if (event.key === " " && overlay.available) {
            event.preventDefault();
            overlay.toggle();
        }
    }

    document.addEventListener("keyup", onKey);
    rendition.on("keyup", onKey);

    /* ------------------------------------------------------------------ */
    /* Progress sync                                                       */
    /* ------------------------------------------------------------------ */

    let syncTimer = null;

    rendition.on("relocated", function (location) {
        updateProgressLabel(location);
        if (suppressProgressSync) {
            return;
        }
        clearTimeout(syncTimer);
        syncTimer = setTimeout(function () {
            const cfi = location.start.cfi;
            const progress = locationsReady ? book.locations.percentageFromCfi(cfi) : 0;
            api("/reading-progress", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({bookId: bookId, location: cfi, progress: progress || 0})
            }).catch(error => console.warn("Progress sync failed", error));
        }, 1500);
    });

    function updateProgressLabel(location) {
        if (!locationsReady || !location) {
            progressEl.textContent = "";
            return;
        }
        const percentage = book.locations.percentageFromCfi(location.start.cfi);
        progressEl.textContent = Math.round((percentage || 0) * 100) + "%";
    }

    /* ------------------------------------------------------------------ */
    /* Media overlays (read-aloud)                                         */
    /* ------------------------------------------------------------------ */

    const overlay = {
        available: false,
        playing: false,
        audio: new Audio(),
        manifest: null,     // id -> {href, mediaType, mediaOverlay}
        opfDir: "",
        smilCache: new Map(),
        pars: [],           // current chapter: [{fragment, audioHref, begin, end}]
        parsHref: null,     // spine href the pars belong to
        activeIndex: -1,
        activeElement: null,

        async init() {
            try {
                const containerXml = await this.fetchXml("META-INF/container.xml");
                const opfPath = containerXml.querySelector("rootfile").getAttribute("full-path");
                this.opfDir = opfPath.includes("/") ? opfPath.substring(0, opfPath.lastIndexOf("/") + 1) : "";
                const opf = await this.fetchXml(opfPath);
                this.manifest = {};
                let hasOverlays = false;
                for (const item of opf.querySelectorAll("manifest > item, manifest item")) {
                    const entry = {
                        href: item.getAttribute("href"),
                        mediaType: item.getAttribute("media-type"),
                        mediaOverlay: item.getAttribute("media-overlay")
                    };
                    this.manifest[item.getAttribute("id")] = entry;
                    if (entry.mediaOverlay || entry.mediaType === "application/smil+xml") {
                        hasOverlays = true;
                    }
                }
                if (hasOverlays) {
                    this.available = true;
                    readAloudButton.classList.remove("hidden");
                }
            } catch (error) {
                console.warn("Media overlay detection failed", error);
            }
        },

        async fetchXml(entry) {
            const response = await fetch(resourceBase + entry, {credentials: "same-origin"});
            if (!response.ok) {
                throw new Error(`Fetching ${entry} failed: ${response.status}`);
            }
            const text = await response.text();
            return new DOMParser().parseFromString(text, "application/xml");
        },

        /** Loads the SMIL pars for the spine item currently displayed. */
        async loadCurrentSection() {
            const location = rendition.currentLocation();
            if (!location || !location.start) {
                return false;
            }
            const href = location.start.href;
            if (this.parsHref === href && this.pars.length) {
                return true;
            }
            const manifestItem = Object.values(this.manifest).find(item =>
                    this.resolve(this.opfDir, item.href) === href || item.href === href);
            const overlayId = manifestItem ? manifestItem.mediaOverlay : null;
            if (!overlayId || !this.manifest[overlayId]) {
                this.pars = [];
                this.parsHref = href;
                return false;
            }
            const smilEntry = this.resolve(this.opfDir, this.manifest[overlayId].href);
            this.pars = await this.loadSmil(smilEntry);
            this.parsHref = href;
            this.activeIndex = -1;
            return this.pars.length > 0;
        },

        async loadSmil(smilEntry) {
            if (this.smilCache.has(smilEntry)) {
                return this.smilCache.get(smilEntry);
            }
            const smil = await this.fetchXml(smilEntry);
            const smilDir = smilEntry.includes("/") ? smilEntry.substring(0, smilEntry.lastIndexOf("/") + 1) : "";
            const pars = [];
            for (const par of smil.querySelectorAll("par")) {
                const text = par.querySelector("text");
                const audio = par.querySelector("audio");
                if (!text || !audio) continue;
                const src = text.getAttribute("src") || "";
                const fragment = src.includes("#") ? src.substring(src.indexOf("#") + 1) : null;
                if (!fragment) continue;
                pars.push({
                    fragment: fragment,
                    audioHref: this.resolve(smilDir, audio.getAttribute("src")),
                    begin: this.clock(audio.getAttribute("clipBegin")),
                    end: this.clock(audio.getAttribute("clipEnd"))
                });
            }
            this.smilCache.set(smilEntry, pars);
            return pars;
        },

        clock(value) {
            if (!value) return 0;
            value = value.trim();
            if (value.endsWith("ms")) return parseFloat(value) / 1000;
            if (value.endsWith("s")) return parseFloat(value);
            return value.split(":").reduce((total, part) => total * 60 + parseFloat(part), 0);
        },

        /** Resolves ../-style relative hrefs against a directory prefix. */
        resolve(dir, href) {
            const parts = (dir + href).split("/");
            const out = [];
            for (const part of parts) {
                if (part === "..") out.pop();
                else if (part !== "." && part !== "") out.push(part);
            }
            return out.join("/");
        },

        async toggle() {
            if (this.playing) {
                this.pause();
                return;
            }
            const hasPars = await this.loadCurrentSection();
            if (!hasPars) {
                showMessage("This chapter has no read-aloud audio.");
                setTimeout(hideMessage, 2000);
                return;
            }
            const index = this.activeIndex >= 0 ? this.activeIndex : 0;
            this.playPar(index);
        },

        playPar(index) {
            const par = this.pars[index];
            if (!par) {
                this.advanceToNextSection();
                return;
            }
            this.activeIndex = index;
            const src = resourceBase + par.audioHref;
            if (!this.audio.src.endsWith(src)) {
                this.audio.src = src;
            }
            this.audio.currentTime = par.begin;
            this.audio.play().then(() => {
                this.playing = true;
                readAloudButton.classList.add("playing");
                readAloudButton.innerHTML = "&#10074;&#10074;";
            }).catch(error => {
                console.warn("Audio playback failed", error);
                // Autoplay without a user gesture is blocked by most browsers; point at the button.
                showMessage(error && error.name === "NotAllowedError"
                        ? "Tap ▶ to start reading along."
                        : "Could not play the audio.");
                setTimeout(hideMessage, 3000);
            });
        },

        pause() {
            this.audio.pause();
            this.playing = false;
            readAloudButton.classList.remove("playing");
            readAloudButton.innerHTML = "&#9654;";
        },

        onTimeUpdate() {
            if (!this.playing || this.activeIndex < 0) return;
            const time = this.audio.currentTime;
            const current = this.pars[this.activeIndex];
            if (!current) return;
            if (time >= current.end - 0.05) {
                const next = this.activeIndex + 1;
                if (next < this.pars.length) {
                    const nextPar = this.pars[next];
                    // Same audio file and contiguous: let it run. Otherwise seek explicitly.
                    this.activeIndex = next;
                    if (nextPar.audioHref !== current.audioHref || Math.abs(nextPar.begin - current.end) > 0.3) {
                        this.playPar(next);
                        return;
                    }
                    this.highlight(nextPar.fragment);
                } else {
                    this.advanceToNextSection();
                    return;
                }
            }
            if (this.activeElement === null && this.pars[this.activeIndex]) {
                this.highlight(this.pars[this.activeIndex].fragment);
            }
        },

        async advanceToNextSection() {
            this.clearHighlight();
            this.pars = [];
            this.parsHref = null;
            this.activeIndex = -1;
            await rendition.next();
            const hasPars = await this.loadCurrentSection();
            if (hasPars) {
                this.playPar(0);
            } else {
                this.pause();
            }
        },

        highlight(fragment) {
            this.clearHighlight();
            const contents = rendition.getContents();
            for (const content of contents) {
                const element = content.document.getElementById(fragment);
                if (element) {
                    element.classList.add("-epub-media-overlay-active");
                    this.activeElement = element;
                    this.turnPageIfNeeded(content, element);
                    return;
                }
            }
        },

        clearHighlight() {
            if (this.activeElement) {
                this.activeElement.classList.remove("-epub-media-overlay-active");
                this.activeElement = null;
            }
        },

        /** Turns the page when the highlighted fragment scrolled out of the visible columns. */
        turnPageIfNeeded(content, element) {
            try {
                const cfi = content.cfiFromNode(element);
                const location = rendition.currentLocation();
                if (!location || !location.end) return;
                const comparator = new ePub.CFI();
                if (comparator.compare(cfi, location.end.cfi) > 0) {
                    rendition.next();
                } else if (comparator.compare(cfi, location.start.cfi) < 0) {
                    rendition.display(cfi);
                }
            } catch (error) {
                // Best effort: a failed comparison only means no automatic page turn.
            }
        },

        /** Tap a sentence to jump the audio there. */
        attachTapToSeek(contents) {
            contents.document.addEventListener("click", (event) => {
                if (!this.available || !this.pars.length) return;
                let node = event.target;
                while (node && node !== contents.document.body) {
                    if (node.id) {
                        const index = this.pars.findIndex(par => par.fragment === node.id);
                        if (index >= 0) {
                            this.playPar(index);
                            return;
                        }
                    }
                    node = node.parentElement;
                }
            });
        }
    };

    overlay.audio.addEventListener("timeupdate", () => overlay.onTimeUpdate());
    overlay.audio.addEventListener("ended", () => overlay.advanceToNextSection());
    readAloudButton.addEventListener("click", () => overlay.toggle());

    rendition.on("rendered", function (section, view) {
        if (view && view.contents) {
            overlay.attachTapToSeek(view.contents);
        }
    });

    rendition.on("relocated", function () {
        // Manual page/section change while paused: forget stale pars so play resumes here.
        if (!overlay.playing) {
            overlay.parsHref = null;
            overlay.activeIndex = -1;
            overlay.clearHighlight();
        }
    });

    overlayReady = book.opened.then(() => overlay.init());
})();
