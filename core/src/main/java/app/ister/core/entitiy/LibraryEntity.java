package app.ister.core.entitiy;

import app.ister.core.enums.LibraryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
public class LibraryEntity extends BaseEntity {
    @Column(nullable = false)
    private LibraryType libraryType;

    @Column(nullable = false, unique = true)
    private String name;
}
