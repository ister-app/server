package app.ister.core.entity;

import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A user's custom view ("smart playlist"): a named filter definition over one browse kind,
 * optionally scoped to one library. The filter column holds the {@code MediaFilter} JSON.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class SavedViewEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    /** Null for a view over every library the user can see. */
    @ManyToOne
    private LibraryEntity libraryEntity;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FilterKind kind;

    @Column(nullable = false, columnDefinition = "text")
    private String filter;

    @Enumerated(EnumType.STRING)
    private SortingEnum sorting;

    @Enumerated(EnumType.STRING)
    private SortingOrder sortingOrder;
}
