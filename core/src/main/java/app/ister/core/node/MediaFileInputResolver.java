package app.ister.core.node;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.NodeEntity;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where a media file's bytes can be read from on <em>this</em> node: the local path when the
 * file's directory is owned by this node, else a tokenized download URL on the owning node.
 * Helper nodes (transcoding, intro detection, subtitle extraction for another node's
 * directories) feed that URL to ffmpeg; the owner serves it with byte ranges so seeks stay
 * cheap.
 *
 * <p>Every method walks {@code mediaFile.directoryEntity.nodeEntity}; call it inside the
 * transaction that loaded the entity, or after initializing that chain — listener threads have
 * no open session.
 */
@Component
public class MediaFileInputResolver {

    private final NodeTokenManager nodeTokenManager;
    private final String localNodeName;

    public MediaFileInputResolver(NodeTokenManager nodeTokenManager,
                                  @Value("${app.ister.server.name}") String localNodeName) {
        this.nodeTokenManager = nodeTokenManager;
        this.localNodeName = localNodeName;
    }

    public boolean isRemote(MediaFileEntity mediaFile) {
        return !localNodeName.equals(owner(mediaFile).getName());
    }

    /** The node that owns the file's directory. */
    public NodeEntity owner(MediaFileEntity mediaFile) {
        return mediaFile.getDirectoryEntity().getNodeEntity();
    }

    /** Local path or tokenized {@code /mediaFile/{id}/download} URL, usable as an ffmpeg input. */
    public String resolve(MediaFileEntity mediaFile) {
        if (!isRemote(mediaFile)) {
            return mediaFile.getPath();
        }
        return owner(mediaFile).getUrl()
                + "/mediaFile/" + mediaFile.getId()
                + "/download?token=" + nodeTokenManager.getDownloadToken();
    }

    /**
     * Tokenized URL of an external subtitle file ({@code EXTERNAL_SUBTITLE} stream) on the
     * owning node. Its {@code path} is owner-local (cache directory or a sidecar next to the
     * media), so a remote node can only get at it through this endpoint.
     */
    public String subtitleDownloadUrl(MediaFileEntity mediaFile, MediaFileStreamEntity stream) {
        return owner(mediaFile).getUrl()
                + "/mediaFileStream/" + stream.getId()
                + "/download?token=" + nodeTokenManager.getDownloadToken();
    }

    /** The input without its {@code ?token=} for logging: node tokens must not land in the logs. */
    public static String stripToken(String input) {
        int idx = input.indexOf("?token=");
        return idx < 0 ? input : input.substring(0, idx);
    }

    /**
     * An ffmpeg input for a local path or a remote download URL. For URLs ffmpeg's http client
     * gets its reconnect options: a proxy or gateway that closes the response early otherwise
     * makes ffmpeg treat the truncated stream as a clean end of file, and a helper node would
     * store, say, half a subtitle without any error. With these it resumes with a Range request.
     */
    public static UrlInput ffmpegInput(String input) {
        UrlInput urlInput = UrlInput.fromUrl(input);
        if (isUrl(input)) {
            urlInput.addArguments("-reconnect", "1")
                    .addArguments("-reconnect_on_network_error", "1")
                    .addArguments("-reconnect_streamed", "1")
                    .addArguments("-reconnect_delay_max", "30");
        }
        return urlInput;
    }

    public static boolean isUrl(String input) {
        return input.startsWith("http://") || input.startsWith("https://");
    }
}
