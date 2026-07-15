package app.ister.core.entity;

import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A user's browse settings for one library: which key to sort the grid on and in which direction.
 * The default when no row exists is NAME / ASCENDING, the order the library grids have always had.
 * At most one row per (user, library) — enforced by a unique index in Flyway.
 */
@Entity
@Table(name = "user_library_preference")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserLibraryPreferenceEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Getter(onMethod = @__(@JsonBackReference))
    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SortingEnum sorting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SortingOrder sortingOrder;
}
