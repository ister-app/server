package app.ister.core.entity;

import app.ister.core.enums.GameForm;
import app.ister.core.enums.GamePlatform;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

/**
 * A game in a GAME library. Played on the client, never streamed: the player downloads the files,
 * starts a local emulator (or the game itself) and syncs the user's saves back here.
 *
 * <p>Two file models. Single-file forms (ROM, PC zip, Linux installer/portable) attach their
 * file(s) as {@link MediaFileEntity} rows via {@code gameEntity}, like comic volumes. Folder forms
 * ({@link GameForm#isFolder()}: PS3, Steam) are one row with {@link #rootPath} and <em>no</em>
 * per-file rows — such a game is tens of thousands of files, which would swamp the media-file
 * table and the scan bookkeeping — and are listed live by the game resource controller.
 */
@Entity
@Table(name = "game_entity", uniqueConstraints = @UniqueConstraint(
        name = "game_entity_identity", columnNames = {"libraryEntityId", "platform", "name", "pathYear"}))
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class GameEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private LibraryEntity libraryEntity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GamePlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private GameForm form;

    /** Path identity: the "Title" of the game folder. Never rewritten from metadata. */
    @Column(nullable = false)
    private String name;

    /** Display override (PARAM.SFO title, the Steam name, a metadata provider). NULL = {@link #name}. */
    private String title;

    @Column(nullable = false)
    private int releaseYear;

    /** The "(YYYY)" suffix of the game folder (0 = none); part of the identity. */
    @Column(nullable = false)
    private int pathYear;

    /** Folder forms: the library directory the game folder lives in. NULL for single-file forms. */
    @ManyToOne(fetch = FetchType.LAZY)
    private DirectoryEntity directoryEntity;

    /** Folder forms: the game folder (absolute path, or {@code s3://bucket/key}). */
    @Column(columnDefinition = "text")
    private String rootPath;

    /** What the client launches, relative to the game folder / archive root. NULL = the media file itself. */
    @Column(columnDefinition = "text")
    private String entryPath;

    /** PS3 title id from PARAM.SFO (e.g. BLUS12345); names RPCS3's savedata and trophy folders. */
    @Column(length = 16)
    private String titleId;

    private Long steamAppId;

    /** The folder name under {@code steamapps/common}, from the .acf {@code installdir}. */
    private String steamInstallDir;

    private Long steamBuildId;

    /** Ludusavi reports Steam Cloud sync for this game; the client warns about double sync. */
    @Column(nullable = false)
    private boolean steamCloud;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private int fileCount;

    /** JSON array of save-location templates set by an admin; always wins over the manifest. */
    @Column(columnDefinition = "text")
    private String savePaths;

    /** JSON array of save-location templates resolved from the Ludusavi manifest. */
    @Column(columnDefinition = "text")
    private String manifestSavePaths;

    /** How the manifest entry was found: STEAM_ID, TITLE, or NONE when nothing matched. */
    @Column(length = 16)
    private String manifestMatchedBy;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "gameEntity")
    @OrderBy("path ASC")
    private List<MediaFileEntity> mediaFileEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "gameEntity")
    @OrderBy("id ASC")
    private List<ImageEntity> imageEntities;

    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "gameEntity")
    private List<MetadataEntity> metadataEntities;

    /** The title to show: the metadata/SFO/Steam title when known, else the path name. */
    public String displayTitle() {
        return title != null && !title.isBlank() ? title : name;
    }

    /** The library directory id for folder forms, without initialising the lazy directory proxy. */
    public UUID directoryEntityId() {
        return directoryEntity == null ? null : directoryEntity.getId();
    }
}
