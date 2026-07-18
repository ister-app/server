package app.ister.core.entity;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Grants one user visibility of one restricted library (a library with visibleToAll = false).
 * At most one row per (user, library) — enforced by a unique index in Flyway.
 */
@Entity
@Table(name = "user_library_access")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserLibraryAccessEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;
}
