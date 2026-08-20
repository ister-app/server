package app.ister.core.entity;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Person identity: two spellings of one name must resolve to one person row. "ABBA" and "Abba" are
 * the same artist, and so are "Alice DeeJay" and "ALICE  DEEJAY".
 *
 * <p>{@link #normalize} must mirror the generated {@code person_entity.name_normalized} column
 * exactly ({@code lower(btrim(regexp_replace(name, '\s+', ' ', 'g')))}); the Postgres integration
 * test asserts that the two agree.
 */
public final class PersonNames {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private PersonNames() {
    }

    public static String normalize(String name) {
        if (name == null) return null;
        return WHITESPACE.matcher(name.strip()).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
