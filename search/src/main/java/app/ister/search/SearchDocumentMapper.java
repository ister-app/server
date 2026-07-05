package app.ister.search;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SearchEntityType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SearchDocumentMapper {

    public SearchDocument toDocument(MovieEntity movie) {
        MetadataEntity metadata = preferredMetadata(movie.getMetadataEntities());
        return SearchDocument.builder()
                .id(movie.getId().toString())
                .type(SearchEntityType.MOVIE.name())
                .title(movie.getName())
                .description(description(metadata))
                .genre(genre(metadata))
                .year(movie.getReleaseYear())
                .libraryId(libraryId(movie.getLibraryEntity()))
                .build();
    }

    public SearchDocument toDocument(ShowEntity show) {
        MetadataEntity metadata = preferredMetadata(show.getMetadataEntities());
        return SearchDocument.builder()
                .id(show.getId().toString())
                .type(SearchEntityType.SHOW.name())
                .title(show.getName())
                .description(description(metadata))
                .genre(genre(metadata))
                .year(show.getReleaseYear())
                .libraryId(libraryId(show.getLibraryEntity()))
                .build();
    }

    public SearchDocument toDocument(EpisodeEntity episode) {
        MetadataEntity metadata = preferredMetadata(episode.getMetadataEntities());
        String fallbackTitle = "%s S%dE%d".formatted(
                episode.getShowEntity().getName(),
                episode.getSeasonEntity().getNumber(),
                episode.getNumber());
        return SearchDocument.builder()
                .id(episode.getId().toString())
                .type(SearchEntityType.EPISODE.name())
                .title(title(metadata, fallbackTitle))
                .context(episode.getShowEntity().getName())
                .description(description(metadata))
                .genre(genre(metadata))
                .number(episode.getNumber())
                .seasonNumber(episode.getSeasonEntity().getNumber())
                .libraryId(libraryId(episode.getShowEntity().getLibraryEntity()))
                .build();
    }

    public SearchDocument toDocument(PersonEntity person) {
        MetadataEntity metadata = preferredMetadata(person.getMetadataEntities());
        return SearchDocument.builder()
                .id(person.getId().toString())
                .type(SearchEntityType.PERSON.name())
                .title(person.getName())
                .description(description(metadata))
                .genre(genre(metadata))
                .year(person.getBirthYear())
                .libraryId(libraryId(person.getLibraryEntity()))
                .build();
    }

    public SearchDocument toDocument(AlbumEntity album) {
        MetadataEntity metadata = preferredMetadata(album.getMetadataEntities());
        return SearchDocument.builder()
                .id(album.getId().toString())
                .type(SearchEntityType.ALBUM.name())
                .title(album.getName())
                .context(album.getPersonEntity().getName())
                .description(description(metadata))
                .genre(genre(metadata))
                .year(album.getReleaseYear())
                .libraryId(libraryId(album.getLibraryEntity()))
                .build();
    }

    public SearchDocument toDocument(TrackEntity track) {
        MetadataEntity metadata = preferredMetadata(track.getMetadataEntities());
        return SearchDocument.builder()
                .id(track.getId().toString())
                .type(SearchEntityType.TRACK.name())
                .title(title(metadata, "Track " + track.getNumber()))
                .context(track.getPersonEntity().getName() + " – " + track.getAlbumEntity().getName())
                .description(description(metadata))
                .genre(genre(metadata))
                .number(track.getNumber())
                .libraryId(libraryId(track.getAlbumEntity().getLibraryEntity()))
                .build();
    }

    /** Prefers English metadata (ISO-639-3), falls back to the first entry. */
    private MetadataEntity preferredMetadata(List<MetadataEntity> metadataEntities) {
        if (metadataEntities == null || metadataEntities.isEmpty()) {
            return null;
        }
        return metadataEntities.stream()
                .filter(metadata -> "eng".equals(metadata.getLanguage()))
                .findFirst()
                .orElse(metadataEntities.getFirst());
    }

    private String title(MetadataEntity metadata, String fallback) {
        return metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()
                ? metadata.getTitle()
                : fallback;
    }

    private String description(MetadataEntity metadata) {
        return metadata == null ? null : metadata.getDescription();
    }

    private String genre(MetadataEntity metadata) {
        return metadata == null ? null : metadata.getGenre();
    }

    private String libraryId(LibraryEntity libraryEntity) {
        return libraryEntity == null ? null : libraryEntity.getId().toString();
    }
}
