package app.ister.core.node;

import app.ister.core.config.S3Properties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.NodeEntity;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.storage.ObjectRef;
import app.ister.core.storage.ObjectStoreRegistry;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Where a media file's bytes can be read from on <em>this</em> node.
 *
 * <ul>
 *   <li>LOCAL directory owned by this node: the local path.</li>
 *   <li>LOCAL directory owned by another node: a tokenized download URL on that node. Helper
 *       nodes (transcoding, intro detection, subtitle extraction for another node's directories)
 *       feed that URL to ffmpeg; the owner serves it with byte ranges so seeks stay cheap.</li>
 *   <li>S3 directory this node is attached to: the node's own {@code /mediaFile/{id}/download}
 *       proxy over the loopback address (S3 stays unreachable from the ffmpeg side and the
 *       node-token auth path is reused), or a presigned S3 URL when
 *       {@code app.ister.s3.ffmpeg-direct} is on.</li>
 *   <li>S3 directory this node is <em>not</em> attached to (a helper listing the directory name):
 *       the download URL on any attached node.</li>
 * </ul>
 *
 * <p>Every method walks {@code mediaFile.directoryEntity.nodeEntity}; call it inside the
 * transaction that loaded the entity, or after initializing that chain — listener threads have
 * no open session.
 */
@Component
public class MediaFileInputResolver {

    private final NodeTokenManager nodeTokenManager;
    private final ObjectStoreRegistry objectStoreRegistry;
    private final S3Properties s3Properties;
    private final DirectoryRepository directoryRepository;
    private final String localNodeName;
    private final String selfUrl;

    public MediaFileInputResolver(NodeTokenManager nodeTokenManager,
                                  ObjectStoreRegistry objectStoreRegistry,
                                  S3Properties s3Properties,
                                  DirectoryRepository directoryRepository,
                                  @Value("${app.ister.server.name}") String localNodeName,
                                  @Value("${app.ister.server.self-url:http://127.0.0.1:${server.port:8080}}") String selfUrl) {
        this.nodeTokenManager = nodeTokenManager;
        this.objectStoreRegistry = objectStoreRegistry;
        this.s3Properties = s3Properties;
        this.directoryRepository = directoryRepository;
        this.localNodeName = localNodeName;
        this.selfUrl = selfUrl.endsWith("/") ? selfUrl.substring(0, selfUrl.length() - 1) : selfUrl;
    }

    public boolean isS3(MediaFileEntity mediaFile) {
        return mediaFile.getDirectoryEntity().isS3();
    }

    /** Whether the bytes have to come from another node. */
    public boolean isRemote(MediaFileEntity mediaFile) {
        return remoteNode(mediaFile).isPresent();
    }

    /**
     * The node the bytes must be fetched from, empty when this node can read them itself: the
     * owner of a LOCAL directory, or — for an S3 directory this node has no connection for — any
     * attached node.
     */
    public Optional<NodeEntity> remoteNode(MediaFileEntity mediaFile) {
        DirectoryEntity directory = mediaFile.getDirectoryEntity();
        if (directory.isS3()) {
            if (objectStoreRegistry.byConnection(directory.getS3Connection()).isPresent()) {
                return Optional.empty();
            }
            return directoryRepository.findAttachedNodes(directory.getId()).stream()
                    .filter(n -> !localNodeName.equals(n.getName()))
                    .findFirst();
        }
        NodeEntity owner = directory.getNodeEntity();
        return localNodeName.equals(owner.getName()) ? Optional.empty() : Optional.of(owner);
    }

    public Optional<String> remoteNodeUrl(MediaFileEntity mediaFile) {
        return remoteNode(mediaFile).map(NodeEntity::getUrl);
    }

    /** The owning node of a LOCAL directory. S3 directories have no owner: throws. */
    public NodeEntity owner(MediaFileEntity mediaFile) {
        NodeEntity owner = mediaFile.getDirectoryEntity().getNodeEntity();
        if (owner == null) {
            throw new IllegalStateException("S3 directory " + mediaFile.getDirectoryEntity().getName() + " has no owning node");
        }
        return owner;
    }

    /** Local path or a URL, usable as an ffmpeg input. */
    public String resolve(MediaFileEntity mediaFile) {
        Optional<NodeEntity> remote = remoteNode(mediaFile);
        if (remote.isPresent()) {
            return downloadUrl(remote.get().getUrl(), mediaFile);
        }
        if (isS3(mediaFile)) {
            if (s3Properties.isFfmpegDirect()) {
                return objectStoreRegistry.forEntity(mediaFile)
                        .presignGet(ObjectRef.parse(mediaFile.getPath()).key(), s3Properties.getPresignTtl());
            }
            return downloadUrl(selfUrl, mediaFile);
        }
        return mediaFile.getPath();
    }

    private String downloadUrl(String nodeUrl, MediaFileEntity mediaFile) {
        return nodeUrl + "/mediaFile/" + mediaFile.getId() + "/download?token=" + nodeTokenManager.getDownloadToken();
    }

    /**
     * Tokenized URL of an external subtitle file ({@code EXTERNAL_SUBTITLE} stream) on the node
     * that holds it. Its {@code path} is node-local (cache directory or a sidecar next to the
     * media), so a remote node can only get at it through this endpoint.
     */
    public String subtitleDownloadUrl(MediaFileEntity mediaFile, MediaFileStreamEntity stream) {
        String nodeUrl = remoteNodeUrl(mediaFile).orElse(selfUrl);
        return nodeUrl + "/mediaFileStream/" + stream.getId() + "/download?token=" + nodeTokenManager.getDownloadToken();
    }

    /** The input without its {@code ?token=} for logging: node tokens must not land in the logs. */
    public static String stripToken(String input) {
        int idx = input.indexOf("?token=");
        if (idx < 0) {
            // presigned S3 URLs carry the signature in the query as well
            idx = input.indexOf("?X-Amz-");
        }
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
