package app.ister.core.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"directoryEntityId", "path"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MediaFileEntity extends FileFromPathEntity {

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private MovieEntity movieEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = true)
    private EpisodeEntity episodeEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @Setter
    @ManyToOne(optional = true)
    private TrackEntity trackEntity;

    /** Set for epub files: they attach to the book directly, not to a chapter. */
    @Getter(onMethod = @__(@JsonBackReference))
    @Setter
    @ManyToOne(optional = true)
    private BookEntity bookEntity;

    /** Set for audiobook audio files. */
    @Getter(onMethod = @__(@JsonBackReference))
    @Setter
    @ManyToOne(optional = true)
    private ChapterEntity chapterEntity;

    /** Set for downloaded podcast episode audio (lives on the cache directory). */
    @Getter(onMethod = @__(@JsonBackReference))
    @Setter
    @ManyToOne(optional = true)
    private PodcastEpisodeEntity podcastEpisodeEntity;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "mediaFileEntity")
    private List<MediaFileStreamEntity> mediaFileStreamEntity;

    @Column(nullable = false)
    private long size;

    private long durationInMilliseconds;

    /**
     * True for epub files that contain EPUB 3 media overlays (read-aloud audio with SMIL timing).
     * Detected from the epub contents, never from the filename. Null for non-epub files.
     */
    private Boolean mediaOverlays;

    /**
     * ISBN from the epub OPF (dc:identifier), normalized to bare ISBN-10/13. Used for exact
     * Open Library matching — a translated edition's ISBN resolves to the original work.
     */
    private String isbn;
}
