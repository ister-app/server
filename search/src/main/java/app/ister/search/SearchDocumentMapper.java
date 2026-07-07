package app.ister.search;

import app.ister.core.config.LanguageProperties;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchDocumentMapper {

    private final LanguageProperties languageProperties;

    public SearchDocumentMapper(LanguageProperties languageProperties) {
        this.languageProperties = languageProperties;
    }

    public SearchDocument toDocument(MovieEntity movie) {
        Map<String, MetadataEntity> byLanguage = byLanguage(movie.getMetadataEntities());
        SearchDocument.Builder builder = SearchDocument.builder()
                .id(movie.getId().toString())
                .type(SearchEntityType.MOVIE.name())
                .title(movie.getName())
                .year(movie.getReleaseYear())
                .libraryId(libraryId(movie.getLibraryEntity()));
        addLocalized(builder, byLanguage);
        return builder.build();
    }

    public SearchDocument toDocument(ShowEntity show) {
        Map<String, MetadataEntity> byLanguage = byLanguage(show.getMetadataEntities());
        SearchDocument.Builder builder = SearchDocument.builder()
                .id(show.getId().toString())
                .type(SearchEntityType.SHOW.name())
                .title(show.getName())
                .year(show.getReleaseYear())
                .libraryId(libraryId(show.getLibraryEntity()));
        addLocalized(builder, byLanguage);
        return builder.build();
    }

    public SearchDocument toDocument(EpisodeEntity episode) {
        List<MetadataEntity> metadata = episode.getMetadataEntities();
        String fallbackTitle = "%s S%dE%d".formatted(
                episode.getShowEntity().getName(),
                episode.getSeasonEntity().getNumber(),
                episode.getNumber());
        SearchDocument.Builder builder = SearchDocument.builder()
                .id(episode.getId().toString())
                .type(SearchEntityType.EPISODE.name())
                .title(title(preferredMetadata(metadata), fallbackTitle))
                .context(episode.getShowEntity().getName())
                .number(episode.getNumber())
                .seasonNumber(episode.getSeasonEntity().getNumber())
                .libraryId(libraryId(episode.getShowEntity().getLibraryEntity()));
        addLocalized(builder, byLanguage(metadata));
        return builder.build();
    }

    public SearchDocument toDocument(PersonEntity person) {
        Map<String, MetadataEntity> byLanguage = byLanguage(person.getMetadataEntities());
        SearchDocument.Builder builder = SearchDocument.builder()
                .id(person.getId().toString())
                .type(SearchEntityType.PERSON.name())
                .title(person.getName())
                .year(person.getBirthYear())
                .libraryId(libraryId(person.getLibraryEntity()));
        addLocalized(builder, byLanguage);
        return builder.build();
    }

    public SearchDocument toDocument(AlbumEntity album) {
        Map<String, MetadataEntity> byLanguage = byLanguage(album.getMetadataEntities());
        SearchDocument.Builder builder = SearchDocument.builder()
                .id(album.getId().toString())
                .type(SearchEntityType.ALBUM.name())
                .title(album.getName())
                .context(album.getPersonEntity().getName())
                .year(album.getReleaseYear())
                .libraryId(libraryId(album.getLibraryEntity()));
        addLocalized(builder, byLanguage);
        return builder.build();
    }

    public SearchDocument toDocument(TrackEntity track) {
        List<MetadataEntity> metadata = track.getMetadataEntities();
        SearchDocument.Builder builder = SearchDocument.builder()
                .id(track.getId().toString())
                .type(SearchEntityType.TRACK.name())
                .title(title(preferredMetadata(metadata), "Track " + track.getNumber()))
                .context(track.getPersonEntity().getName() + " – " + track.getAlbumEntity().getName())
                .number(track.getNumber())
                .libraryId(libraryId(track.getAlbumEntity().getLibraryEntity()));
        addLocalized(builder, byLanguage(metadata));
        return builder.build();
    }

    /** Adds per-language {@code title_<tag>}/{@code description_<tag>}/{@code genre_<tag>} fields. */
    private void addLocalized(SearchDocument.Builder builder, Map<String, MetadataEntity> byLanguage) {
        for (String tag : languageProperties.tags()) {
            MetadataEntity metadata = byLanguage.get(languageProperties.iso3(tag));
            if (metadata == null) {
                continue;
            }
            builder.localized("title", tag, metadata.getTitle())
                    .localized("description", tag, metadata.getDescription())
                    .localized("genre", tag, metadata.getGenre());
        }
    }

    /** Indexes metadata by its ISO-639-3 language code; on duplicates the first entry wins. */
    private Map<String, MetadataEntity> byLanguage(List<MetadataEntity> metadataEntities) {
        Map<String, MetadataEntity> byLanguage = new HashMap<>();
        if (metadataEntities != null) {
            for (MetadataEntity metadata : metadataEntities) {
                if (metadata.getLanguage() != null) {
                    byLanguage.putIfAbsent(metadata.getLanguage(), metadata);
                }
            }
        }
        return byLanguage;
    }

    /** Prefers the primary language's metadata, falling back to the first available entry. */
    private MetadataEntity preferredMetadata(List<MetadataEntity> metadataEntities) {
        if (metadataEntities == null || metadataEntities.isEmpty()) {
            return null;
        }
        String primaryIso3 = languageProperties.iso3(languageProperties.primaryTag());
        return metadataEntities.stream()
                .filter(metadata -> primaryIso3.equals(metadata.getLanguage()))
                .findFirst()
                .orElse(metadataEntities.getFirst());
    }

    private String title(MetadataEntity metadata, String fallback) {
        return metadata != null && metadata.getTitle() != null && !metadata.getTitle().isBlank()
                ? metadata.getTitle()
                : fallback;
    }

    private String libraryId(LibraryEntity libraryEntity) {
        return libraryEntity == null ? null : libraryEntity.getId().toString();
    }
}
