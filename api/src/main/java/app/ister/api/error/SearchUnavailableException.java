package app.ister.api.error;

/** Thrown when a search request arrives while Typesense is not configured on this server. */
public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException() {
        super("Search is not configured on this server");
    }
}
