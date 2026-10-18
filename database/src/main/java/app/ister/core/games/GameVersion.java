package app.ister.core.games;

import app.ister.core.entity.GameEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * The version a client compares to find out whether an installed game changed on the server.
 *
 * <p>The same value is served twice — as {@code Game.version} in GraphQL and as {@code version}
 * (plus the ETag) of {@code GET /game/{id}/manifest} — so it is computed in one place from the
 * entity alone. A digest over the manifest's file list would be the more precise choice, but the
 * file list of a folder game is only available in the disk module (it is walked live from the
 * game folder), and the api module cannot reach it. Hence the rule is:
 *
 * <ul>
 *   <li>a Steam game's {@code steamBuildId} (Steam's own build number from the .acf), when set;</li>
 *   <li>otherwise the first 16 hex characters of SHA-256 over
 *       {@code sizeBytes|fileCount|dateUpdatedEpochMillis}. The scanner rewrites size and count
 *       whenever the tree changed, and {@code dateUpdated} ({@code @LastModifiedDate}) moves with
 *       every real change of the row, so a re-upload or new build yields a new version. A
 *       metadata-only edit (title, save-path override) also bumps it, which is harmless: the client
 *       then revalidates the files by their own ETags.</li>
 * </ul>
 */
public final class GameVersion {

    private GameVersion() {
    }

    public static String of(GameEntity game) {
        if (game.getSteamBuildId() != null) {
            return game.getSteamBuildId().toString();
        }
        Instant updated = game.getDateUpdated();
        String material = game.getSizeBytes() + "|" + game.getFileCount() + "|"
                + (updated == null ? 0L : updated.toEpochMilli());
        return sha256Prefix(material);
    }

    private static String sha256Prefix(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
