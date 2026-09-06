package app.ister.disk.events.subtitleextract;

import java.util.Collection;
import java.util.Set;

/** A spelling dictionary for one language, as the OCR cleanup needs it. */
public interface Lexicon {

    /** The subset of {@code words} the dictionary does not accept (in any capitalisation it allows). */
    Set<String> unknown(Collection<String> words);

    /**
     * True when the word is listed in the dictionary in exactly this capitalised spelling,
     * i.e. it is a name ("Jerry", "Elaine") and not just the capitalised form of a common word.
     */
    boolean isProperNoun(String word);
}
