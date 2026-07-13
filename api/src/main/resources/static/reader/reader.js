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
 *
 * The same book can also be listened to as an audiobook (separate chapter files, played by the
 * native app), and both have to resume in the same place. Translating between the two coordinate
 * systems happens here, in `sync`, because this is the only component that knows the epub's
 * structure. It reads both positions from /book-progress, opens at whichever was touched last, and
 * writes both back on every save. Where a read-aloud edition exists and its SMIL timeline matches
 * the audiobook's chapter durations, the mapping is sentence-exact; otherwise it interpolates
 * within the chapter, which is accurate to about a paragraph.
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

    const explicitChapter = chapterIndex !== null && !isNaN(chapterIndex) && chapterIndex >= 0;
    let bookProgress = null;

    /** A stored reading location only means something inside the epub it was recorded in. */
    function savedReadingLocation() {
        const reading = bookProgress && bookProgress.reading;
        if (!reading || !reading.location) return null;
        if (reading.mediaFileId && reading.mediaFileId !== mediaFileId) return null;
        return reading.location;
    }

    /**
     * A requested chapter wins over the saved position; otherwise resume where the user left off.
     * The progress is fetched either way: even when opening at a chapter, saving has to map the
     * reading position back onto the audiobook, which needs the chapter list.
     */
    async function startLocation() {
        bookProgress = await api(`/book-progress?bookId=${bookId}`).catch(() => null);
        if (explicitChapter) {
            const href = await chapterHref(chapterIndex);
            if (href) {
                return href;
            }
        }
        return savedReadingLocation() || undefined;
    }

    /**
     * Opens at the audiobook position when that is the newer of the two. Runs after the first
     * display because the mapping needs the generated locations (and, for the exact mode, the media
     * overlays), which are only available by then; the jump is why progress sync stays suppressed
     * until this settles — otherwise the initial position would overwrite the very position we are
     * about to restore.
     */
    async function resumeFromNewestPosition() {
        if (explicitChapter || !bookProgress) return;
        const listening = sync.listeningPosition();
        const reading = bookProgress.reading;
        const readingTime = savedReadingLocation() && reading.updatedAt ? Date.parse(reading.updatedAt) : 0;

        if (!listening || listening.updatedAt <= readingTime) {
            // Nothing newer on the audio side. A reading position from the *other* epub edition is
            // still worth something: its percentage lands us on roughly the right page here.
            if (!savedReadingLocation() && reading && reading.progress > 0) {
                const cfi = book.locations.cfiFromPercentage(reading.progress);
                if (cfi) await rendition.display(cfi);
            }
            return;
        }
        const cfi = await sync.audioToCfi(listening.chapterIndex, listening.positionInMilliseconds);
        if (cfi) {
            await rendition.display(cfi);
        }
    }

    Promise.resolve()
        .then(startLocation)
        .then(location => rendition.display(location))
        .then(function () {
            hideMessage();
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
            return overlayReady;
        })
        .then(() => sync.init(bookProgress ? bookProgress.chapters : []))
        .then(resumeFromNewestPosition)
        .catch(function (error) {
            console.error(error);
            showMessage("Could not load the book.");
        })
        .then(function () {
            suppressProgressSync = false;
            updateProgressLabel(rendition.currentLocation());
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
        syncTimer = setTimeout(async function () {
            const cfi = location.start.cfi;
            const progress = locationsReady ? book.locations.percentageFromCfi(cfi) : 0;
            // Store the equivalent audiobook position too, so listening picks up where reading
            // stopped. The audiobook is the only representation the native player understands.
            const audio = await sync.cfiToAudio(cfi).catch(function (error) {
                console.warn("Could not map the reading position onto the audiobook", error);
                return null;
            });
            api("/reading-progress", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({
                    bookId: bookId,
                    location: cfi,
                    progress: progress || 0,
                    readingLocationMediaFileId: mediaFileId,
                    chapterId: audio ? audio.chapterId : null,
                    positionInMilliseconds: audio ? audio.positionInMilliseconds : null
                })
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
    /* Audio ⇄ text position mapping                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Translates between an audiobook position (chapter + milliseconds) and a text position
     * (epubcfi), in both directions.
     *
     * Audiobook chapters are separate files that know nothing about the epub, so they are aligned
     * with the spine positionally: through the TOC when it has one entry per chapter, otherwise
     * through the spine itself. Books where neither lines up fall back to mapping the whole book
     * proportionally, which is coarse but never wrong by more than the unevenness of the chapters.
     *
     * Within a chapter there are two modes. When the book has a read-aloud edition whose SMIL
     * timeline matches the chapter's duration, every sentence has a clip time and the mapping is
     * exact. Otherwise the position is interpolated over the chapter's share of the generated
     * locations, which lands within a paragraph or two.
     */
    const sync = {
        chapters: [],        // audiobook chapters, ordered, from /book-progress
        sections: null,      // chapter index -> spine Section; null when they could not be aligned
        chapterBySpine: new Map(),
        ranges: new Map(),   // spine index -> {first, last} indexes into book.locations
        parsCache: new Map(),// chapter index -> pars, or [] when unusable for mapping
        cfiCache: new Map(), // chapter index -> Map(fragment -> cfi)

        get totalDuration() {
            return this.chapters.reduce((total, chapter) => total + (chapter.durationInMilliseconds || 0), 0);
        },

        /** Aligns the audiobook chapters with the spine. Called once the book and locations are up. */
        async init(chapters) {
            this.chapters = chapters || [];
            if (!this.chapters.length) {
                return;
            }
            this.sections = await this.alignSections();
            if (this.sections) {
                this.sections.forEach((section, index) => this.chapterBySpine.set(section.index, index));
            } else {
                console.info("Chapters could not be aligned with the spine; mapping the book proportionally.");
            }
            this.indexLocations();
        },

        async alignSections() {
            const count = this.chapters.length;
            try {
                const navigation = await book.loaded.navigation;
                const toc = (navigation && navigation.toc) || [];
                if (toc.length === count) {
                    const sections = toc.map(entry => book.spine.get(entry.href));
                    if (sections.every(Boolean)) {
                        return sections;
                    }
                }
            } catch (error) {
                console.warn("Could not read the navigation", error);
            }
            const spineItems = book.spine.spineItems || [];
            return spineItems.length === count ? spineItems.slice() : null;
        },

        /** Which slice of the generated locations belongs to each spine item. */
        indexLocations() {
            const locations = book.locations._locations || [];
            locations.forEach((cfi, index) => {
                const section = book.spine.get(cfi);
                if (!section) return;
                const range = this.ranges.get(section.index);
                if (range) {
                    range.last = index;
                } else {
                    this.ranges.set(section.index, {first: index, last: index});
                }
            });
        },

        /**
         * The SMIL pars of a chapter, but only when they can be trusted as that chapter's timeline:
         * the read-aloud audio has to be the same recording as the audiobook file, which we check by
         * comparing the SMIL's end time with the chapter's duration.
         */
        async parsFor(chapterIndex) {
            if (this.parsCache.has(chapterIndex)) {
                return this.parsCache.get(chapterIndex);
            }
            let pars = [];
            const section = this.sections && this.sections[chapterIndex];
            if (section) {
                pars = await overlay.parsForHref(section.href).catch(() => []);
                const duration = this.chapters[chapterIndex].durationInMilliseconds;
                const smilEnd = pars.length ? pars[pars.length - 1].end * 1000 : 0;
                const tolerance = Math.max(2000, (duration || 0) * 0.02);
                if (!duration || !pars.length || Math.abs(smilEnd - duration) > tolerance) {
                    if (pars.length && duration) {
                        console.info(`Chapter ${chapterIndex}: SMIL timeline (${Math.round(smilEnd)}ms) does not `
                                + `match the audiobook file (${duration}ms); interpolating instead.`);
                    }
                    pars = [];
                }
            }
            this.parsCache.set(chapterIndex, pars);
            return pars;
        },

        /** fragment id -> cfi for a chapter's SMIL pars, resolved against the loaded section. */
        async fragmentCfis(chapterIndex, pars) {
            if (this.cfiCache.has(chapterIndex)) {
                return this.cfiCache.get(chapterIndex);
            }
            const section = this.sections[chapterIndex];
            const cfis = new Map();
            try {
                await section.load(book.load.bind(book));
                for (const par of pars) {
                    const element = section.document && section.document.getElementById(par.fragment);
                    if (element) {
                        cfis.set(par.fragment, section.cfiFromElement(element));
                    }
                }
            } catch (error) {
                console.warn("Could not resolve the sentences of chapter " + chapterIndex, error);
            }
            this.cfiCache.set(chapterIndex, cfis);
            return cfis;
        },

        /** The cfi at [fraction] through a spine item, via its slice of the generated locations. */
        cfiInSection(spineIndex, fraction) {
            const locations = book.locations._locations || [];
            const range = this.ranges.get(spineIndex);
            if (!range || !locations.length) {
                return null;
            }
            const index = Math.round(range.first + Math.min(Math.max(fraction, 0), 1) * (range.last - range.first));
            return locations[Math.min(index, locations.length - 1)];
        },

        /** Where in the book (0–1) a chapter position falls, treating the audiobook as one timeline. */
        bookFraction(chapterIndex, positionInMilliseconds) {
            const total = this.totalDuration;
            if (!total) return 0;
            let elapsed = 0;
            for (let i = 0; i < chapterIndex; i++) {
                elapsed += this.chapters[i].durationInMilliseconds || 0;
            }
            return Math.min((elapsed + positionInMilliseconds) / total, 1);
        },

        /** The text position of an audiobook position. */
        async audioToCfi(chapterIndex, positionInMilliseconds) {
            const chapter = this.chapters[chapterIndex];
            if (!chapter) return null;
            const section = this.sections && this.sections[chapterIndex];
            if (!section) {
                return book.locations.cfiFromPercentage(this.bookFraction(chapterIndex, positionInMilliseconds));
            }

            const pars = await this.parsFor(chapterIndex);
            if (pars.length) {
                const seconds = positionInMilliseconds / 1000;
                let par = pars[0];
                for (const candidate of pars) {
                    if (candidate.begin <= seconds) par = candidate;
                    else break;
                }
                const cfis = await this.fragmentCfis(chapterIndex, pars);
                const cfi = cfis.get(par.fragment);
                if (cfi) {
                    return cfi;
                }
            }

            const duration = chapter.durationInMilliseconds || 0;
            const fraction = duration ? positionInMilliseconds / duration : 0;
            return this.cfiInSection(section.index, fraction)
                    || book.locations.cfiFromPercentage(this.bookFraction(chapterIndex, positionInMilliseconds));
        },

        /** The audiobook position of a text position, as {chapterId, positionInMilliseconds}. */
        async cfiToAudio(cfi) {
            if (!this.chapters.length) return null;
            const section = book.spine.get(cfi);
            const chapterIndex = section ? this.chapterBySpine.get(section.index) : undefined;
            if (chapterIndex === undefined) {
                return this.audioPositionFromFraction(book.locations.percentageFromCfi(cfi) || 0);
            }
            const chapter = this.chapters[chapterIndex];

            const pars = await this.parsFor(chapterIndex);
            if (pars.length) {
                const cfis = await this.fragmentCfis(chapterIndex, pars);
                const comparator = new ePub.CFI();
                let begin = null;
                for (const par of pars) {
                    const parCfi = cfis.get(par.fragment);
                    if (!parCfi) continue;
                    try {
                        if (comparator.compare(parCfi, cfi) > 0) break;
                    } catch (error) {
                        continue;
                    }
                    begin = par.begin;
                }
                if (begin !== null) {
                    return {chapterId: chapter.id, positionInMilliseconds: Math.round(begin * 1000)};
                }
            }

            const range = this.ranges.get(section.index);
            const locationIndex = book.locations.locationFromCfi(cfi);
            const duration = chapter.durationInMilliseconds || 0;
            let fraction = 0;
            if (range && range.last > range.first && locationIndex >= 0) {
                fraction = (locationIndex - range.first) / (range.last - range.first);
            }
            return {
                chapterId: chapter.id,
                positionInMilliseconds: Math.round(Math.min(Math.max(fraction, 0), 1) * duration)
            };
        },

        /** Fallback for unaligned books: a fraction of the book is a fraction of the audiobook. */
        audioPositionFromFraction(fraction) {
            const total = this.totalDuration;
            if (!total) return null;
            let target = Math.min(Math.max(fraction, 0), 1) * total;
            for (const chapter of this.chapters) {
                const duration = chapter.durationInMilliseconds || 0;
                if (target <= duration || chapter === this.chapters[this.chapters.length - 1]) {
                    return {chapterId: chapter.id, positionInMilliseconds: Math.round(Math.min(target, duration))};
                }
                target -= duration;
            }
            return null;
        },

        /**
         * Where listening left off: the chapter touched last, or the next one when it was finished
         * — the same resume rule the player uses. Null when the book was never listened to.
         */
        listeningPosition() {
            let latest = -1;
            let latestTime = 0;
            this.chapters.forEach((chapter, index) => {
                if (!chapter.updatedAt) return;
                const time = Date.parse(chapter.updatedAt);
                if (time >= latestTime) {
                    latestTime = time;
                    latest = index;
                }
            });
            if (latest < 0) return null;
            let index = latest;
            let position = this.chapters[latest].progressInMilliseconds || 0;
            if (this.chapters[latest].watched && latest + 1 < this.chapters.length) {
                index = latest + 1;
                position = this.chapters[index].progressInMilliseconds || 0;
            }
            return {chapterIndex: index, positionInMilliseconds: position, updatedAt: latestTime};
        }
    };

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

        /** The SMIL pars of an arbitrary spine item, or [] when it has no media overlay. */
        async parsForHref(href) {
            if (!this.manifest || !href) {
                return [];
            }
            const manifestItem = Object.values(this.manifest).find(item =>
                    this.resolve(this.opfDir, item.href) === href || item.href === href);
            const overlayId = manifestItem ? manifestItem.mediaOverlay : null;
            if (!overlayId || !this.manifest[overlayId]) {
                return [];
            }
            return this.loadSmil(this.resolve(this.opfDir, this.manifest[overlayId].href));
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
            this.pars = await this.parsForHref(href);
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
