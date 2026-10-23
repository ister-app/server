package app.ister.worker.events.gamefound;

import app.ister.core.games.GameSavePaths.SavePath;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.FileAccess;
import app.ister.core.storage.ObjectStat;
import app.ister.worker.http.MetadataRestClients;
import org.jspecify.annotations.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Where PC games keep their saves, from the community-maintained
 * <a href="https://github.com/mtkennerly/ludusavi-manifest">Ludusavi manifest</a> (compiled from
 * PCGamingWiki). The ~10 MB YAML is downloaded at most once per {@code refresh-hours}, kept in the
 * cache store under {@code ludusavi/manifest.yaml}, and reduced to two small in-memory indexes
 * (Steam app id → entry, normalized title → entry); the full object graph is dropped after parsing.
 *
 * <p>Matching is strict on purpose: a Steam app id, or an exact match on the normalized title.
 * Fuzzy matching would snapshot the wrong folders, which is worse than no snapshot — the admin
 * can always set the paths by hand.
 */
@Slf4j
@Service
public class LudusaviManifestService {

    static final String CACHE_KEY = "ludusavi/manifest.yaml";
    private static final Set<String> OS_VALUES = Set.of("windows", "linux", "mac");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    public enum MatchedBy { STEAM_ID, TITLE, NONE }

    /** One manifest entry, reduced to what the save sync needs. */
    public record Entry(String name, List<SavePath> savePaths, boolean steamCloud, @Nullable Long steamId) {}

    public record Match(Entry entry, MatchedBy matchedBy) {
        public static final Match NONE = new Match(null, MatchedBy.NONE);
    }

    private final RestClient restClient;
    private final CacheDirectoryResolver cacheDirectoryResolver;
    private final FileAccess fileAccess;
    private final String manifestUrl;
    private final Duration refreshInterval;
    private final boolean enabled;
    private final Object lock = new Object();

    private final AtomicReference<Index> index = new AtomicReference<>();

    record Index(Map<Long, Entry> bySteamId, Map<String, Entry> byTitle, Instant loadedAt) {}

    public LudusaviManifestService(
            @Value("${app.ister.worker.ludusavi.manifest-url:https://raw.githubusercontent.com/mtkennerly/ludusavi-manifest/master/data/manifest.yaml}") String manifestUrl,
            @Value("${app.ister.worker.ludusavi.refresh-hours:168}") long refreshHours,
            @Value("${app.ister.worker.ludusavi.enabled:true}") boolean enabled,
            CacheDirectoryResolver cacheDirectoryResolver,
            FileAccess fileAccess) {
        this.manifestUrl = manifestUrl;
        this.refreshInterval = Duration.ofHours(Math.max(1, refreshHours));
        this.enabled = enabled;
        this.cacheDirectoryResolver = cacheDirectoryResolver;
        this.fileAccess = fileAccess;
        this.restClient = MetadataRestClients.json().mutate()
                .defaultHeader("Accept", "text/plain, application/x-yaml, */*")
                .requestFactory(longReadFactory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory longReadFactory() {
        // ~10 MB on a slow link; the JDK client keeps the socket read off the listener's virtual thread
        return app.ister.core.http.JdkHttpClients.requestFactory(Duration.ofSeconds(10), Duration.ofMinutes(2));
    }

    /** The manifest entry for a game: by Steam app id when known, else by exact normalized title. */
    public Match find(@Nullable Long steamAppId, String title) {
        if (!enabled) {
            return Match.NONE;
        }
        Index current = currentIndex();
        if (current == null) {
            return Match.NONE;
        }
        if (steamAppId != null) {
            Entry bySteam = current.bySteamId().get(steamAppId);
            if (bySteam != null) {
                return new Match(bySteam, MatchedBy.STEAM_ID);
            }
        }
        if (title != null) {
            Entry byTitle = current.byTitle().get(normalize(title));
            if (byTitle != null) {
                return new Match(byTitle, MatchedBy.TITLE);
            }
        }
        return Match.NONE;
    }

    /** Lowercase, trademark signs and punctuation gone, "&" as "and", single spaces, no leading article. */
    static String normalize(String title) {
        String s = title.toLowerCase(Locale.ROOT)
                .replace("™", "").replace("®", "").replace("©", "")
                .replace("&", " and ")
                .replace("'", "").replace("’", "");
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        s = NON_ALNUM.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").strip();
        if (s.startsWith("the ")) {
            s = s.substring(4);
        }
        return s;
    }

    private Index currentIndex() {
        Index current = index.get();
        if (current != null && current.loadedAt().plus(refreshInterval).isAfter(Instant.now())) {
            return current;
        }
        synchronized (lock) {
            current = index.get();
            if (current != null && current.loadedAt().plus(refreshInterval).isAfter(Instant.now())) {
                return current;
            }
            try {
                index.set(new Index(new HashMap<>(), new HashMap<>(), Instant.now())); // placeholder while loading
                Index loaded = load();
                index.set(loaded);
                return loaded;
            } catch (RuntimeException | IOException e) {
                log.warn("Ludusavi manifest unavailable: {}", e.getMessage());
                // Keep an empty index for a short while so a flapping download does not hammer the source.
                index.set(new Index(Map.of(), Map.of(), Instant.now().minus(refreshInterval).plus(Duration.ofMinutes(15))));
                return null;
            }
        }
    }

    private Index load() throws IOException {
        CacheStore store = cacheDirectoryResolver.store();
        String storedPath = store.pathFor(CACHE_KEY);
        Optional<ObjectStat> cached = store.stat(storedPath);
        boolean fresh = cached.isPresent() && cached.get().lastModified() != null
                && cached.get().lastModified().plus(refreshInterval).isAfter(Instant.now());
        if (!fresh) {
            Optional<byte[]> downloaded = download();
            if (downloaded.isPresent()) {
                store.write(CACHE_KEY, downloaded.get(), "application/x-yaml");
                return parse(downloaded.get());
            }
            if (cached.isEmpty()) {
                throw new IOException("no cached manifest and the download failed");
            }
            log.warn("Using the stale cached Ludusavi manifest");
        }
        try (InputStream in = fileAccess.open(store.directory(), storedPath)) {
            return parse(in.readAllBytes());
        }
    }

    private Optional<byte[]> download() {
        try {
            log.info("Downloading Ludusavi manifest from {}", manifestUrl);
            return Optional.ofNullable(restClient.get().uri(manifestUrl).retrieve().body(byte[].class));
        } catch (RestClientException e) {
            log.warn("Ludusavi manifest download failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Reduces the manifest to the two lookup maps. Aliases resolve to their target entry. */
    @SuppressWarnings("unchecked")
    static Index parse(byte[] yamlBytes) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setMaxAliasesForCollections(Integer.MAX_VALUE);
        options.setAllowDuplicateKeys(true);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object root = yaml.load(new String(yamlBytes, StandardCharsets.UTF_8));
        if (!(root instanceof Map<?, ?> games)) {
            throw new IllegalArgumentException("Ludusavi manifest is not a mapping");
        }
        Map<Long, Entry> bySteamId = new HashMap<>();
        Map<String, Entry> byTitle = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        for (Map.Entry<?, ?> raw : games.entrySet()) {
            if (!(raw.getKey() instanceof String name) || !(raw.getValue() instanceof Map<?, ?> body)) {
                continue;
            }
            if (body.get("alias") instanceof String target) {
                aliases.put(name, target);
            } else {
                Entry entry = toEntry(name, (Map<String, Object>) body);
                if (entry.steamId() != null) {
                    bySteamId.putIfAbsent(entry.steamId(), entry);
                }
                byTitle.putIfAbsent(normalize(name), entry);
            }
        }
        aliases.forEach((name, target) -> {
            Entry entry = byTitle.get(normalize(target));
            if (entry != null) {
                byTitle.putIfAbsent(normalize(name), entry);
            }
        });
        log.info("Ludusavi manifest indexed: {} games, {} with a Steam id", byTitle.size(), bySteamId.size());
        return new Index(Map.copyOf(bySteamId), Map.copyOf(byTitle), Instant.now());
    }

    private static Entry toEntry(String name, Map<String, Object> body) {
        List<SavePath> paths = new ArrayList<>();
        if (body.get("files") instanceof Map<?, ?> files) {
            files.forEach((key, value) -> {
                if (key instanceof String path) {
                    paths.addAll(savePaths(path, value));
                }
            });
        }
        Long steamId = null;
        if (body.get("steam") instanceof Map<?, ?> steam && steam.get("id") instanceof Number id) {
            steamId = id.longValue();
        }
        boolean steamCloud = body.get("cloud") instanceof Map<?, ?> cloud && Boolean.TRUE.equals(cloud.get("steam"));
        return new Entry(name, List.copyOf(paths), steamCloud, steamId);
    }

    /** One {@link SavePath} per OS a save-tagged manifest path applies to; nothing for config-only paths. */
    @SuppressWarnings("unchecked")
    private static List<SavePath> savePaths(String path, Object rawConstraints) {
        Map<String, Object> constraints = rawConstraints instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (!isSave(constraints)) {
            return List.of();
        }
        return osValues(constraints).stream().map(os -> new SavePath(path, os)).toList();
    }

    /** Entries tagged {@code save} (or untagged: Ludusavi backs those up too); config-only ones are skipped. */
    private static boolean isSave(Map<String, Object> constraints) {
        Object tags = constraints.get("tags");
        if (!(tags instanceof List<?> list) || list.isEmpty()) {
            return true;
        }
        return list.stream().anyMatch(tag -> "save".equalsIgnoreCase(String.valueOf(tag)));
    }

    /**
     * The operating systems a path applies to: one per {@code when} clause naming an OS, a single
     * null (any) when no clause constrains the OS. Clauses that only name a store are "any OS".
     */
    private static List<String> osValues(Map<String, Object> constraints) {
        Object when = constraints.get("when");
        if (!(when instanceof List<?> clauses) || clauses.isEmpty()) {
            return java.util.Collections.singletonList(null);
        }
        List<String> result = new ArrayList<>();
        for (Object clause : clauses) {
            String os = clauseOs(clause);
            if (os == null) {
                return java.util.Collections.singletonList(null); // one unconstrained clause makes the path "any OS"
            }
            if (!result.contains(os)) {
                result.add(os);
            }
        }
        return result;
    }

    /** The known OS a {@code when} clause names, or null when it names none (or an unknown one). */
    @Nullable
    private static String clauseOs(Object clause) {
        if (clause instanceof Map<?, ?> m && m.get("os") instanceof String value) {
            String os = value.toLowerCase(Locale.ROOT);
            return OS_VALUES.contains(os) ? os : null;
        }
        return null;
    }
}
