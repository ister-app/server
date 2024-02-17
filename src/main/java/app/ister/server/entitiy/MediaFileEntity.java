package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor
@Data
public class MediaFileEntity extends BaseEntity {

    @NonNull
    @ManyToOne
    private DiskEntity diskEntity;

    @NonNull
    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne
    private EpisodeEntity episodeEntity;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "mediaFileEntity")
    private List<MediaFileStreamEntity> mediaFileStreamEntity;

    @NonNull
    private String path;

    @NonNull
    private long size;
}
