package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.DirectoryType;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.NodeService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ImageDownloadService {
    private final NodeService nodeService;
    private final DirectoryRepository directoryRepository;
    private final ImageDownload imageDownload;
    private final ImageSave imageSave;

    public void downloadAndSave(String imageUrl, ImageType imageType, String language,
                                String sourceUri,
                                @Nullable MovieEntity movie,
                                @Nullable ShowEntity show,
                                @Nullable EpisodeEntity episode,
                                @Nullable ArtistEntity artist,
                                @Nullable AlbumEntity album) throws IOException {
        var nodeEntity = nodeService.getOrCreateNodeEntityForThisNode();
        var cacheDisk = directoryRepository
                .findByDirectoryTypeAndNodeEntity(DirectoryType.CACHE, nodeEntity)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No cache directory found for this node"));
        String toPath = cacheDisk.getPath() + UUID.randomUUID() + ".jpg";
        imageDownload.download(imageUrl, toPath);
        imageSave.save(cacheDisk, toPath, imageType, language, sourceUri, new ImageSave.MediaEntityRef(movie, show, episode, artist, album));
    }
}
