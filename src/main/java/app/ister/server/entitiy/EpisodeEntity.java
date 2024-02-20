package app.ister.server.entitiy;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.sql.Timestamp;
import java.util.List;

@Entity
@EntityListeners(AuditingEntityListener.class)
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor
@Data
public class EpisodeEntity extends BaseEntity {

    @CreatedDate
    Timestamp dateCreated;
    @LastModifiedDate
    Timestamp dateUpdated;

    @NonNull
    @ManyToOne
    private ShowEntity showEntity;

    @NonNull
    @ManyToOne
    private SeasonEntity seasonEntity;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "episodeEntity")
    private List<ImageEntity> imagesEntities;

    @NonNull
    private int number;
}
