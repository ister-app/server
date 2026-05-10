package app.ister.disk.nfo;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Optional;

@Slf4j
public class Parser {

    private Parser() {}

    private static final String NFO_PARSE_ERROR = "Error with nfo parsing: ";

    public static Optional<ShowNfo> parseShow(String path) throws FileNotFoundException {
        try (var stream = new FileInputStream(path)) {
            return parseShow(stream);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (java.io.IOException _) {
            return Optional.empty();
        }
    }

    public static Optional<ShowNfo> parseShow(InputStream inputStream) {
        try {
            JAXBContext context = JAXBContext.newInstance(ShowNfo.class);
            return Optional.ofNullable((ShowNfo) context.createUnmarshaller()
                    .unmarshal(inputStream));
        } catch (JAXBException | IllegalArgumentException e) {
            log.error(NFO_PARSE_ERROR, e);
        }
        return Optional.empty();
    }

    public static Optional<EpisodeNfo> parseEpisode(String path) throws FileNotFoundException {
        try (var stream = new FileInputStream(path)) {
            return parseEpisode(stream);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (java.io.IOException _) {
            return Optional.empty();
        }
    }

    public static Optional<EpisodeNfo> parseEpisode(InputStream inputStream) {
        try {
            JAXBContext context = JAXBContext.newInstance(EpisodeNfo.class);
            return Optional.ofNullable((EpisodeNfo) context.createUnmarshaller()
                    .unmarshal(inputStream));
        } catch (JAXBException | IllegalArgumentException e) {
            log.error(NFO_PARSE_ERROR, e);
        }
        return Optional.empty();
    }

    public static Optional<MovieNfo> parseMovie(String path) throws FileNotFoundException {
        try (var stream = new FileInputStream(path)) {
            return parseMovie(stream);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (java.io.IOException _) {
            return Optional.empty();
        }
    }

    public static Optional<MovieNfo> parseMovie(InputStream inputStream) {
        try {
            JAXBContext context = JAXBContext.newInstance(MovieNfo.class);
            return Optional.ofNullable((MovieNfo) context.createUnmarshaller()
                    .unmarshal(inputStream));
        } catch (JAXBException | IllegalArgumentException e) {
            log.error(NFO_PARSE_ERROR, e);
        }
        return Optional.empty();
    }

    public static Optional<ArtistNfo> parseArtist(String path) throws FileNotFoundException {
        try (var stream = new FileInputStream(path)) {
            return parseArtist(stream);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (java.io.IOException _) {
            return Optional.empty();
        }
    }

    public static Optional<ArtistNfo> parseArtist(InputStream inputStream) {
        try {
            JAXBContext context = JAXBContext.newInstance(ArtistNfo.class);
            return Optional.ofNullable((ArtistNfo) context.createUnmarshaller()
                    .unmarshal(inputStream));
        } catch (JAXBException | IllegalArgumentException e) {
            log.error(NFO_PARSE_ERROR, e);
        }
        return Optional.empty();
    }

    public static Optional<AlbumNfo> parseAlbum(String path) throws FileNotFoundException {
        try (var stream = new FileInputStream(path)) {
            return parseAlbum(stream);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (java.io.IOException _) {
            return Optional.empty();
        }
    }

    public static Optional<AlbumNfo> parseAlbum(InputStream inputStream) {
        try {
            JAXBContext context = JAXBContext.newInstance(AlbumNfo.class);
            return Optional.ofNullable((AlbumNfo) context.createUnmarshaller()
                    .unmarshal(inputStream));
        } catch (JAXBException | IllegalArgumentException e) {
            log.error(NFO_PARSE_ERROR, e);
        }
        return Optional.empty();
    }
}
