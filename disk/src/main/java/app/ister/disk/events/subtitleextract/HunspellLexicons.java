package app.ister.disk.events.subtitleextract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Hunspell-backed {@link Lexicon}s, one per OCR language. Spell checking shells out to the
 * {@code hunspell} binary (one process per subtitle file, all words on stdin), which gives the
 * full affix and compounding rules for free; the proper-noun check reads the raw {@code .dic}
 * entries once, because hunspell itself accepts the capitalised form of every common word.
 * A language whose dictionary or binary is missing yields {@link Optional#empty()} and is logged once.
 */
@Component
@Slf4j
public class HunspellLexicons {

    /** hunspell binary; the images install it next to tesseract. */
    @Value("${app.ister.server.subtitle-ocr-hunspell:hunspell}")
    private String binary = "hunspell";

    /** Directory holding {@code <name>.dic} + {@code <name>.aff}. */
    @Value("${app.ister.server.subtitle-ocr-dictionary-dir:/usr/share/hunspell}")
    private String dictionaryDir = "/usr/share/hunspell";

    /** ISO-639-3 OCR language → hunspell dictionary name, e.g. {@code eng=en_US,nld=nl_NL}. */
    @Value("${app.ister.server.subtitle-ocr-dictionaries:eng=en_US,nld=nl_NL}")
    private String dictionaries = "eng=en_US,nld=nl_NL";

    private final Map<String, Optional<Lexicon>> cache = new ConcurrentHashMap<>();

    public HunspellLexicons() {
    }

    HunspellLexicons(String binary, String dictionaryDir, String dictionaries) {
        this.binary = binary;
        this.dictionaryDir = dictionaryDir;
        this.dictionaries = dictionaries;
    }

    /** The lexicon for an OCR language, or empty when none is configured or installed. */
    public Optional<Lexicon> forLanguage(String lang) {
        return cache.computeIfAbsent(lang, this::load);
    }

    private Optional<Lexicon> load(String lang) {
        String name = mapping().get(lang);
        if (name == null) {
            return Optional.empty();
        }
        Path base = Path.of(dictionaryDir, name);
        Path dic = base.resolveSibling(name + ".dic");
        Path aff = base.resolveSibling(name + ".aff");
        if (!Files.isReadable(dic) || !Files.isReadable(aff)) {
            log.warn("No hunspell dictionary {} for OCR language {} (expected {}); the OCR cleanup falls back to its built-in rules",
                    name, lang, dic);
            return Optional.empty();
        }
        try {
            Set<String> properNouns = readProperNouns(dic, charsetOf(aff));
            HunspellLexicon lexicon = new HunspellLexicon(binary, base, properNouns);
            // Probe the binary once so a missing install is reported at first use, not per file.
            lexicon.unknown(List.of("probe"));
            log.info("OCR cleanup uses hunspell dictionary {} for language {} ({} proper nouns)", name, lang, properNouns.size());
            return Optional.of(lexicon);
        } catch (IOException e) {
            log.warn("hunspell is not usable for OCR language {} ({}); the OCR cleanup falls back to its built-in rules",
                    lang, e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, String> mapping() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : dictionaries.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                map.put(pair.substring(0, eq).strip(), pair.substring(eq + 1).strip());
            }
        }
        return map;
    }

    private static Charset charsetOf(Path aff) throws IOException {
        try (Stream<String> lines = Files.lines(aff, StandardCharsets.ISO_8859_1)) {
            return lines.filter(l -> l.startsWith("SET "))
                    .map(l -> l.substring(4).strip())
                    .findFirst()
                    .map(HunspellLexicons::charsetOrUtf8)
                    .orElse(StandardCharsets.UTF_8);
        }
    }

    private static Charset charsetOrUtf8(String name) {
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** The entries that start with a capital: the dictionary's names. Everything else hunspell answers itself. */
    static Set<String> readProperNouns(Path dic, Charset charset) throws IOException {
        Set<String> nouns = new HashSet<>();
        try (Stream<String> lines = Files.lines(dic, charset)) {
            lines.skip(1).forEach(line -> {
                int end = line.length();
                int slash = line.indexOf('/');
                int tab = line.indexOf('\t');
                if (slash >= 0) {
                    end = slash;
                }
                if (tab >= 0 && tab < end) {
                    end = tab;
                }
                String entry = line.substring(0, end).strip();
                if (!entry.isEmpty() && Character.isUpperCase(entry.charAt(0))) {
                    nouns.add(entry);
                }
            });
        }
        return nouns;
    }

    /** One dictionary: {@code hunspell -d <base> -l} for spelling, the raw entries for names. */
    static final class HunspellLexicon implements Lexicon {
        private final String binary;
        private final Path base;
        private final Set<String> properNouns;

        HunspellLexicon(String binary, Path base, Set<String> properNouns) {
            this.binary = binary;
            this.base = base;
            this.properNouns = properNouns;
        }

        @Override
        public Set<String> unknown(Collection<String> words) {
            try {
                return misspelled(words);
            } catch (IOException e) {
                log.warn("hunspell failed ({}); leaving this file's spelling as OCR produced it", e.getMessage());
                return Set.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Set.of();
            }
        }

        private Set<String> misspelled(Collection<String> words) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(binary, "-d", base.toString(), "-i", "UTF-8", "-l")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Thread feeder = Thread.ofVirtual().start(() -> {
                try (Writer in = process.outputWriter(StandardCharsets.UTF_8)) {
                    for (String word : words) {
                        in.write(word);
                        in.write('\n');
                    }
                } catch (IOException e) {
                    log.debug("hunspell closed its input early: {}", e.getMessage());
                }
            });
            Set<String> bad = new HashSet<>();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    bad.add(line.strip());
                }
            }
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("hunspell did not finish within 60s");
            }
            feeder.join();
            if (process.exitValue() != 0 && bad.isEmpty()) {
                throw new IOException("hunspell exited with " + process.exitValue());
            }
            return bad;
        }

        @Override
        public boolean isProperNoun(String word) {
            return properNouns.contains(word);
        }
    }
}
