package app.ister.core.entity;

import app.ister.core.enums.PlaylistType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * A per-user playlist over exactly one library. MANUAL playlists hold explicit
 * {@link PlaylistItemEntity items}; SMART playlists embed a {@code MediaFilter} definition
 * (the filter column holds its JSON) with the browse kind it targets and the play order of
 * the resolved items. Strictly personal, like {@link SavedViewEntity}.
 */
@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlaylistEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaylistType type;

    /** SMART only: which browse kind the embedded filter targets. */
    @Enumerated(EnumType.STRING)
    private FilterKind filterKind;

    /** SMART only: the embedded {@code MediaFilter} JSON. */
    @Column(columnDefinition = "text")
    private String filter;

    /** SMART only: play/browse order of the resolved items. */
    @Enumerated(EnumType.STRING)
    private SortingEnum sorting;

    @Enumerated(EnumType.STRING)
    private SortingOrder sortingOrder;

    @Builder.Default
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "playlistEntity", orphanRemoval = true)
    @OrderBy("position ASC")
    private List<PlaylistItemEntity> items = new ArrayList<>();
}
