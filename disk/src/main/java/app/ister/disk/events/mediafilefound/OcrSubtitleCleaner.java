package app.ister.disk.events.mediafilefound;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs the systematic misreads tesseract makes on DVD subtitle bitmaps. In the
 * bold DVD fonts the dot of an "i" touches its stem, so a lower-case "i" comes out
 * as a capital "I" or an "l" ("this Is what", "golng", "nlet"), and a "j" as "J"
 * ("Als Je"). No model or threshold fixes that — the glyphs really are ambiguous —
 * so this pass applies three deliberately narrow, language-specific rules:
 * <ol>
 *   <li>a capitalised function word that continues a sentence is lower-cased
 *       ("here Is home" → "here is home"; "Is it?" at a sentence start stays);</li>
 *   <li>a token that is not a word but is the l-for-i misread of one is replaced
 *       ("wlth" → "with", "nlet" → "niet");</li>
 *   <li>the suffix "lng", which no English or Dutch word ends in, becomes "ing".</li>
 * </ol>
 * Languages without a rule set are left untouched.
 */
@Component
@Slf4j
public class OcrSubtitleCleaner {

    /** Words that, capitalised mid-sentence, are a misread "i"/"j" rather than a name. */
    private static final Map<String, Set<String>> FUNCTION_WORDS = Map.of(
            "eng", Set.of("is", "it", "in", "if", "its", "it's", "isn't", "into"),
            "nld", Set.of("is", "in", "ik", "iets", "ieder", "je", "jij", "jou", "jouw", "jullie", "ja")
    );

    /** Non-words that are the l-for-i misread of a common word. */
    private static final Map<String, Map<String, String>> L_FOR_I = Map.of(
            "eng", Map.ofEntries(
                    Map.entry("lt", "it"), Map.entry("ls", "is"), Map.entry("lf", "if"), Map.entry("ln", "in"),
                    Map.entry("lts", "its"), Map.entry("lt's", "it's"), Map.entry("lsn't", "isn't"),
                    Map.entry("wlth", "with"), Map.entry("thls", "this"), Map.entry("wlll", "will"),
                    Map.entry("llke", "like"), Map.entry("tlme", "time"), Map.entry("llttle", "little"),
                    Map.entry("rlght", "right"), Map.entry("nlght", "night"), Map.entry("thlng", "thing"),
                    Map.entry("thlnk", "think"), Map.entry("glve", "give"), Map.entry("llve", "live"),
                    Map.entry("walt", "wait"), Map.entry("whlch", "which"), Map.entry("whlle", "while"),
                    Map.entry("frlend", "friend"), Map.entry("mlnd", "mind"), Map.entry("klnd", "kind"),
                    Map.entry("flnd", "find"), Map.entry("hlm", "him"), Map.entry("hls", "his"),
                    Map.entry("dld", "did"), Map.entry("dldn't", "didn't"), Map.entry("wlfe", "wife"),
                    Map.entry("llfe", "life"), Map.entry("agaln", "again"), Map.entry("sald", "said")),
            "nld", Map.ofEntries(
                    Map.entry("ls", "is"), Map.entry("ln", "in"), Map.entry("lk", "ik"), Map.entry("hlj", "hij"),
                    Map.entry("zlj", "zij"), Map.entry("wlj", "wij"), Map.entry("mlj", "mij"), Map.entry("jlj", "jij"),
                    Map.entry("zljn", "zijn"), Map.entry("mljn", "mijn"), Map.entry("dlt", "dit"), Map.entry("dle", "die"),
                    Map.entry("nlet", "niet"), Map.entry("lets", "iets"), Map.entry("hler", "hier"), Map.entry("ult", "uit"),
                    Map.entry("lemand", "iemand"), Map.entry("zlch", "zich"), Map.entry("wll", "wil"),
                    Map.entry("nlets", "niets"), Map.entry("mlsschien", "misschien"), Map.entry("tljd", "tijd"),
                    Map.entry("kljk", "kijk"), Map.entry("bljna", "bijna"), Map.entry("altljd", "altijd"),
                    Map.entry("dlng", "ding"), Map.entry("welnig", "weinig"), Map.entry("vrlend", "vriend"))
    );

    /** English words that legitimately end in "lng". Dutch has none. */
    private static final Set<String> LNG_WORDS = Set.of("along", "belong", "oblong", "prolong", "lifelong");

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z'’]+");
    /** The preceding text ends a sentence: the next word may legitimately be capitalised. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.?!\"“…:]\\s*$|[-–]\\s*$|^\\s*$");

    private final Set<String> languagesWithRules = FUNCTION_WORDS.keySet();

    /** Rewrites the cue texts of an SRT file in place; a failure is logged and leaves the file as it was. */
    public void cleanFile(Path srt, String lang) {
        if (!languagesWithRules.contains(lang)) {
            return;
        }
        try {
            String src = Files.readString(srt, StandardCharsets.UTF_8);
            String[] blocks = src.split("\n\n");
            StringBuilder out = new StringBuilder();
            int fixes = 0;
            for (int i = 0; i < blocks.length; i++) {
                String[] lines = blocks[i].split("\n", 3);
                if (lines.length == 3 && lines[1].contains("-->")) {
                    String cleaned = clean(lines[2], lang);
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
            Files.writeString(srt, out.toString(), StandardCharsets.UTF_8);
            log.debug("OCR cleanup touched {} cues in {}", fixes, srt);
        } catch (IOException e) {
            log.warn("OCR cleanup of {} failed, keeping the raw OCR output: {}", srt, e.getMessage());
        }
    }

    /** Cleans one cue text (may span several lines). Unknown languages are returned unchanged. */
    public String clean(String cueText, String lang) {
        Set<String> functionWords = FUNCTION_WORDS.get(lang);
        if (functionWords == null) {
            return cueText;
        }
        Map<String, String> lForI = L_FOR_I.getOrDefault(lang, Map.of());
        StringBuilder out = new StringBuilder();
        Matcher m = TOKEN.matcher(cueText);
        int pos = 0;
        while (m.find()) {
            String token = m.group();
            String before = cueText.substring(0, m.start());
            out.append(cueText, pos, m.start()).append(fixToken(token, before, functionWords, lForI, lang));
            pos = m.end();
        }
        out.append(cueText.substring(pos));
        return out.toString();
    }

    private String fixToken(String token, String before, Set<String> functionWords, Map<String, String> lForI, String lang) {
        String lower = token.toLowerCase(java.util.Locale.ROOT).replace('’', '\'');
        boolean capitalised = Character.isUpperCase(token.charAt(0))
                && token.length() > 1 && token.substring(1).equals(token.substring(1).toLowerCase(java.util.Locale.ROOT));
        if (capitalised && functionWords.contains(lower) && continuesSentence(before)) {
            return Character.toLowerCase(token.charAt(0)) + token.substring(1);
        }
        String replacement = lForI.get(lower);
        if (replacement != null) {
            // A misread that opens a sentence ("lt's with me." after a full stop) gets its capital back.
            boolean opensSentence = !before.isBlank() && !continuesSentence(before);
            return matchCase(opensSentence ? Character.toUpperCase(token.charAt(0)) + token.substring(1) : token, replacement);
        }
        if (lower.length() > 4 && lower.endsWith("lng") && !("eng".equals(lang) && LNG_WORDS.contains(lower))) {
            return token.substring(0, token.length() - 3) + "ing";
        }
        return token;
    }

    /** True when the text before the token is a sentence in progress, so a capital there is a misread. */
    private static boolean continuesSentence(String before) {
        // Only the current cue is context; a token at the very start of it, or after a
        // full stop, question mark, quote or dialogue dash, may be a real capital.
        return !before.isBlank() && !SENTENCE_END.matcher(before).find();
    }

    private static String matchCase(String original, String replacement) {
        if (original.equals(original.toUpperCase(java.util.Locale.ROOT)) && original.length() > 1) {
            return replacement.toUpperCase(java.util.Locale.ROOT);
        }
        if (Character.isUpperCase(original.charAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }

    List<String> supportedLanguages() {
        return List.copyOf(languagesWithRules);
    }
}
