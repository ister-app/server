package app.ister.disk.events.subtitleextract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs the systematic misreads tesseract makes on DVD subtitle bitmaps. In the bold DVD
 * fonts the dot of an "i" touches its stem, so a lower-case "i" comes out as a capital "I" or
 * an "l" ("this Is what", "belleve", "Idea", "Ilke"), and a "j" as "J" ("Just", "Als Je").
 * No model or threshold fixes that — the glyphs really are ambiguous — so this pass repairs
 * them with the language's spelling dictionary ({@link HunspellLexicons}):
 * <ol>
 *   <li>a capitalised function word that continues a sentence is lower-cased
 *       ("here Is home" → "here is home"; "Is it?" at a sentence start stays) — this rule
 *       needs no dictionary and also covers "It", which dictionaries list as an abbreviation;</li>
 *   <li>a word the dictionary knows that starts with a capital I or J mid-sentence is
 *       lower-cased, unless the dictionary lists it as a name ("Just" → "just", "Jerry" stays);</li>
 *   <li>a word the dictionary does not know is replaced when exactly one spelling reached by
 *       reading each i/l/I glyph as the other letter is a word
 *       ("belleve" → "believe", "Devlis" → "Devils", "Jullle" → "jullie", "Elalne" → "Elaine"); a capital I inside a word counts as such a respelling ("DIt" → "dit").</li>
 * </ol>
 * Languages without a dictionary get rule 1 only; languages without rules are left untouched.
 */
@Component
@Slf4j
public class OcrSubtitleCleaner {

    /** Words that, capitalised mid-sentence, are a misread "i"/"j" rather than a name. */
    private static final Map<String, Set<String>> FUNCTION_WORDS = Map.of(
            "eng", Set.of("is", "it", "in", "if", "its", "it's", "isn't", "into"),
            "nld", Set.of("is", "in", "ik", "iets", "ieder", "je", "jij", "jou", "jouw", "jullie", "ja")
    );

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z]++(?:['’][A-Za-z]++)*+");
    /** The preceding text ends a sentence: the next word may legitimately be capitalised. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!\"“…:]\\s*$|[-–]\\s*$|^\\s*$");
    /** More confusable positions than this and the candidate set explodes; such tokens are left alone. */
    private static final int MAX_CONFUSABLE_POSITIONS = 5;

    private final HunspellLexicons lexicons;

    @Autowired
    public OcrSubtitleCleaner(HunspellLexicons lexicons) {
        this.lexicons = lexicons;
    }

    /** For tests: rules only, no dictionary. */
    OcrSubtitleCleaner() {
        this.lexicons = null;
    }

    /** Rewrites the cue texts of an SRT file in place; a failure is logged and leaves the file as it was. */
    public void cleanFile(Path srt, String lang) {
        if (!FUNCTION_WORDS.containsKey(lang)) {
            return;
        }
        Optional<Lexicon> lexicon = lexicons == null ? Optional.empty() : lexicons.forLanguage(lang);
        try {
            // subtile-ocr separates cues with a double blank line and other writers use CRLF:
            // any run of blank lines is a cue boundary, and the file is rewritten with the
            // standard single blank line.
            String src = Files.readString(srt, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            String[] blocks = src.strip().split("\n\s*\n");
            Predicate<String> known = lexicon.map(l -> knownWords(l, cueTexts(blocks))).orElse(null);
            String cleaned = rewrite(blocks, srt, lang, lexicon.orElse(null), known);
            Files.writeString(srt, cleaned, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("OCR cleanup of {} failed, keeping the raw OCR output: {}", srt, e.getMessage());
        }
    }

    /** The three parts of a cue block (index, timing, text), or empty when the block is not a cue. */
    private static Optional<String[]> cueLines(String block) {
        String[] lines = block.strip().split("\n", 3);
        return lines.length == 3 && lines[1].contains("-->") ? Optional.of(lines) : Optional.empty();
    }

    private static List<String> cueTexts(String[] blocks) {
        List<String> cueTexts = new ArrayList<>();
        for (String block : blocks) {
            cueLines(block).ifPresent(lines -> cueTexts.add(lines[2]));
        }
        return cueTexts;
    }

    private String rewrite(String[] blocks, Path srt, String lang, Lexicon lexicon, Predicate<String> known) {
        StringBuilder out = new StringBuilder();
        int fixes = 0;
        for (int i = 0; i < blocks.length; i++) {
            Optional<String[]> cue = cueLines(blocks[i]);
            if (cue.isPresent()) {
                String[] lines = cue.get();
                String cleaned = clean(lines[2], lang, lexicon, known);
                if (!cleaned.equals(lines[2])) {
                    fixes++;
                }
                out.append(lines[0]).append('\n').append(lines[1]).append('\n').append(cleaned);
            } else {
                out.append(blocks[i]);
            }
            if (i < blocks.length - 1) {
                out.append("\n\n");
            }
        }
        log.debug("OCR cleanup touched {} cues in {}", fixes, srt);
        return out.append('\n').toString();
    }

    /** Cleans one cue text with the rules only (no dictionary). Unknown languages are returned unchanged. */
    public String clean(String cueText, String lang) {
        return clean(cueText, lang, null, null);
    }

    /** Cleans one cue text with the given dictionary (tests); {@link #cleanFile} batches the lookups per file instead. */
    String clean(String cueText, String lang, Lexicon lexicon) {
        return clean(cueText, lang, lexicon, knownWords(lexicon, List.of(cueText)));
    }

    private String clean(String cueText, String lang, Lexicon lexicon, Predicate<String> known) {
        Set<String> functionWords = FUNCTION_WORDS.get(lang);
        if (functionWords == null) {
            return cueText;
        }
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(cueText);
        int pos = 0;
        while (m.find()) {
            String token = m.group();
            String before = cueText.substring(0, m.start());
            String fixed = fixFunctionWord(token, before, functionWords);
            if (fixed.equals(token) && lexicon != null) {
                fixed = fixWithDictionary(token, before, lexicon, known);
            }
            out.append(cueText, pos, m.start()).append(fixed);
            pos = m.end();
        }
        out.append(cueText.substring(pos));
        return out.toString();
    }

    private static String fixFunctionWord(String token, String before, Set<String> functionWords) {
        String lower = normalise(token);
        if (isCapitalised(token) && functionWords.contains(lower) && continuesSentence(before)) {
            return Character.toLowerCase(token.charAt(0)) + token.substring(1);
        }
        return token;
    }

    private static String fixWithDictionary(String token, String before, Lexicon lexicon, Predicate<String> known) {
        if (token.length() < 2 || isAllCaps(token)) {
            return token;
        }
        boolean continues = continuesSentence(before);
        if (known.test(normaliseApostrophe(token))) {
            return lowerCaseMisreadCapital(token, lexicon, known, continues);
        }
        return respell(token, before, lexicon, known, continues);
    }

    /** Rule 2: a capital I/J that opens a known common word mid-sentence is the misread dot. */
    private static String lowerCaseMisreadCapital(String token, Lexicon lexicon, Predicate<String> known, boolean continues) {
        if (continues && isCapitalised(token) && "IJ".indexOf(token.charAt(0)) >= 0
                && known.test(normalise(token)) && !lexicon.isProperNoun(normaliseApostrophe(token))) {
            return Character.toLowerCase(token.charAt(0)) + token.substring(1);
        }
        return token;
    }

    /**
     * Rule 3: exactly one confusable-glyph respelling must be a word. The lower-cased token
     * itself is a candidate too: a capital I inside a word ("DIt", "mIJ") is the same misread.
     */
    private static String respell(String token, String before, Lexicon lexicon, Predicate<String> known, boolean continues) {
        String lower = normalise(token);
        Set<String> candidates = new LinkedHashSet<>();
        if (token.indexOf('I', 1) > 0 && (known.test(lower) || known.test(capitalise(lower)))) {
            candidates.add(lower);
        }
        for (String variant : variants(lower)) {
            if (known.test(variant) || known.test(capitalise(variant))) {
                candidates.add(variant);
            }
        }
        if (candidates.size() != 1) {
            return token;
        }
        String word = candidates.iterator().next();
        String result = capitaliseRespelling(token, before, lexicon, known, continues, word) ? capitalise(word) : word;
        return token.indexOf('’') >= 0 ? result.replace('\'', '’') : result;
    }

    private static boolean capitaliseRespelling(String token, String before, Lexicon lexicon,
                                                Predicate<String> known, boolean continues, String word) {
        if (!known.test(word)) {
            return true; // only the capitalised spelling exists: a name ("Elalne" → "Elaine")
        }
        if (Character.isUpperCase(token.charAt(0))) {
            return !continues || lexicon.isProperNoun(capitalise(word));
        }
        // "lt's" after a full stop: the l was a capital I. Any other lower-case first letter was read right.
        return token.charAt(0) == 'l' && !before.isBlank() && !continues;
    }

    /**
     * All spellings reachable by reading each i/l glyph as either letter; the input is lower-case.
     * This covers the plain l-for-i ("belleve"), the swapped pair ("Devlis", "stlil") and the
     * capital-I-for-l ("Ilke", lower-cased to "ilke") in one enumeration.
     */
    static Set<String> variants(String lower) {
        Set<String> out = new LinkedHashSet<>();
        int[] positions = new int[lower.length()];
        int n = 0;
        for (int i = 0; i < lower.length() && n <= MAX_CONFUSABLE_POSITIONS; i++) {
            if (lower.charAt(i) == 'l' || lower.charAt(i) == 'i') {
                positions[n++] = i;
            }
        }
        if (n > MAX_CONFUSABLE_POSITIONS) {
            return out;
        }
        char[] chars = lower.toCharArray();
        for (int mask = 0; mask < 1 << n; mask++) {
            for (int b = 0; b < n; b++) {
                chars[positions[b]] = (mask & 1 << b) == 0 ? 'i' : 'l';
            }
            out.add(new String(chars));
        }
        out.remove(lower);
        return out;
    }

    /** Every word the dictionary will be asked about for these cue texts, so hunspell runs once per file. */
    private static Predicate<String> knownWords(Lexicon lexicon, List<String> cueTexts) {
        Set<String> lookups = new HashSet<>();
        for (String cueText : cueTexts) {
            Matcher m = TOKEN.matcher(cueText);
            while (m.find()) {
                String token = m.group();
                if (token.length() < 2 || isAllCaps(token)) {
                    continue;
                }
                String lower = normalise(token);
                lookups.add(normaliseApostrophe(token));
                lookups.add(lower);
                lookups.add(capitalise(lower));
                for (String variant : variants(lower)) {
                    lookups.add(variant);
                    lookups.add(capitalise(variant));
                }
            }
        }
        Set<String> unknown = lexicon.unknown(new ArrayList<>(lookups));
        return word -> lookups.contains(word) && !unknown.contains(word);
    }

    /** True when the text before the token is a sentence in progress, so a capital there is a misread. */
    private static boolean continuesSentence(String before) {
        // Only the current cue is context; a token at the very start of it, or after a
        // full stop, question mark, quote or dialogue dash, may be a real capital.
        return !before.isBlank() && !SENTENCE_END.matcher(before).find();
    }

    private static boolean isCapitalised(String token) {
        return Character.isUpperCase(token.charAt(0)) && token.length() > 1
                && token.substring(1).equals(token.substring(1).toLowerCase(Locale.ROOT));
    }

    private static boolean isAllCaps(String token) {
        return token.length() > 1 && token.equals(token.toUpperCase(Locale.ROOT));
    }

    private static String normalise(String token) {
        return normaliseApostrophe(token).toLowerCase(Locale.ROOT);
    }

    private static String normaliseApostrophe(String token) {
        return token.replace('’', '\'');
    }

    private static String capitalise(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    List<String> supportedLanguages() {
        return List.copyOf(FUNCTION_WORDS.keySet());
    }
}
