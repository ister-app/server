package app.ister.core.entitiy;

import app.ister.core.enums.StreamCodecType;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"mediaFileEntityId", "streamIndex", "path"}))
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class MediaFileStreamEntity extends BaseEntity {

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = false)
    private MediaFileEntity mediaFileEntity;

    private int streamIndex;

    @Column(nullable = false)
    private String codecName;

    @Column(nullable = false)
    private StreamCodecType codecType;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(nullable = false)
    private String path;

    // https://en.wikipedia.org/wiki/ISO_639-3
    private String language;

    private String title;
}
