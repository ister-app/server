package app.ister.server.nfo;

import app.ister.server.entitiy.EpisodeEntity;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Optional;

@Slf4j
public class Parser {

    public static Optional<ShowNfo> parseShow(String path) throws FileNotFoundException {
        File file = new File(path);
        InputStream stream = new FileInputStream(file);
        return parseShow(stream);
    }

    public static Optional<ShowNfo> parseShow(InputStream inputStream) {
        try {
            JAXBContext context = JAXBContext.newInstance(ShowNfo.class);
            return Optional.ofNullable((ShowNfo) context.createUnmarshaller()
                    .unmarshal(inputStream));
        } catch (JAXBException | IllegalArgumentException e) {
            log.error("Error with nfo parsing: ", e);
        }
        return Optional.empty();
    }

    public static Optional<EpisodeNfo> parseEpisode(String path) throws FileNotFoundException {
        File file = new File(path);
        InputStream stream = new FileInputStream(file);
        return parseEpisode(stream);
    }

    public static Optional<EpisodeNfo> parseEpisode(InputStream inputStream) {
        try {
            JAXBContext context = JAXBContext.newInstance(EpisodeNfo.class);
            return Optional.ofNullable((EpisodeNfo) context.createUnmarshaller()
                    .unmarshal(inputStream));
        } catch (JAXBException | IllegalArgumentException e) {
            log.error("Error with nfo parsing: ", e);
        }
        return Optional.empty();
    }
}
