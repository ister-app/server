package app.ister.server.entitiy;

import app.ister.server.enums.DirectoryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class DirectoryEntity extends BaseEntity {

    @ManyToOne(optional=false)
    private NodeEntity nodeEntity;

    @ManyToOne
    private LibraryEntity libraryEntity;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private DirectoryType directoryType;

}
