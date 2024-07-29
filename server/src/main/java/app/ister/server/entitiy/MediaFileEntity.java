package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "mediaFileEntity")
    private List<MediaFileStreamEntity> mediaFileStreamEntity;

    @Column(nullable = false)
    private long size;

    private long durationInMilliseconds;
}
