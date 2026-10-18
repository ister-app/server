package app.ister.disk.scanner.steam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal parser for Valve's text KeyValues format ("VDF"), enough for Steam's
 * {@code appmanifest_<appid>.acf} and {@code libraryfolders.vdf}:
 *
 * <pre>
 *   "AppState"
 *   {
 *       "appid"      "12345"
 *       "name"       "Some Game"
 *       "UserConfig" { "language" "english" }
 *   }
 * </pre>
 *
 * <p>Values are strings or nested maps; keys keep their case. Backslash escapes ({@code \" \\ \n
 * \t}) inside quoted strings are decoded, {@code //} line comments are skipped, and unquoted
 * tokens are accepted too. Lookups are case-insensitive because Steam itself is.
 */
public final class VdfParser {

    private final String text;
    private int pos;

    private VdfParser(String text) {
        this.text = text;
    }

    /** Parses a whole document into its (usually single) top-level key → value map. */
    public static Map<String, Object> parse(String text) {
        VdfParser parser = new VdfParser(text);
        Map<String, Object> root = new LinkedHashMap<>();
        parser.readPairs(root, false);
        return root;
    }

    /** The nested map under the first top-level key ("AppState", "libraryfolders"), or the root itself. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> body(Map<String, Object> document) {
        if (document.size() == 1 && document.values().iterator().next() instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        return document;
    }

    /** A string value by case-insensitive key. */
    public static Optional<String> string(Map<String, Object> map, String key) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() instanceof String value) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<Long> number(Map<String, Object> map, String key) {
        return string(map, key).flatMap(value -> {
            try {
                return Optional.of(Long.parseLong(value.strip()));
            } catch (NumberFormatException _) {
                return Optional.empty();
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> map(Map<String, Object> map, String key) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() instanceof Map<?, ?> value) {
                return Optional.of((Map<String, Object>) value);
            }
        }
        return Optional.empty();
    }

    private void readPairs(Map<String, Object> into, boolean nested) {
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= text.length()) {
                if (nested) {
                    throw new IllegalArgumentException("Unterminated block in VDF document");
                }
                return;
            }
            char c = text.charAt(pos);
            if (c == '}') {
                pos++;
                if (!nested) {
                    throw new IllegalArgumentException("Unexpected '}' at " + pos);
                }
                return;
            }
            String key = readToken();
            skipWhitespaceAndComments();
            if (pos < text.length() && text.charAt(pos) == '{') {
                pos++;
                Map<String, Object> child = new LinkedHashMap<>();
                readPairs(child, true);
                into.put(key, child);
            } else {
                into.put(key, readToken());
            }
        }
    }

    private String readToken() {
        if (pos >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of VDF document");
        }
        if (text.charAt(pos) == '"') {
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\' && pos < text.length()) {
                    char escaped = text.charAt(pos++);
                    out.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> escaped;
                    });
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string in VDF document");
        }
        int start = pos;
        while (pos < text.length() && !Character.isWhitespace(text.charAt(pos))
                && text.charAt(pos) != '{' && text.charAt(pos) != '}' && text.charAt(pos) != '"') {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("Unexpected character '" + text.charAt(pos) + "' at " + pos);
        }
        return text.substring(start, pos);
    }

    private void skipWhitespaceAndComments() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isWhitespace(c) || c == '\uFEFF') {
                pos++;
            } else if (c == '/' && pos + 1 < text.length() && text.charAt(pos + 1) == '/') {
                while (pos < text.length() && text.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }
}
