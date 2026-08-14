package app.ister.core.service;

import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileEpisodeEntity;
import app.ister.core.repository.MediaFileEpisodeRepository;
import app.ister.core.repository.MediaFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves media files for episodes that live in a multi-episode file (s04e06-e07.mkv).
 *
 * <p>Only the first episode of such a file is referenced by {@code MediaFileEntity.episodeEntity};
 * the others reach the file through {@link MediaFileEpisodeEntity} link rows. Playback paths must
 * therefore use {@link #filesForEpisode} instead of {@code MediaFileRepository.findByEpisodeEntityId}.
 */
@Service
@RequiredArgsConstructor
public class MediaFileEpisodeService {
    private final MediaFileRepository mediaFileRepository;
    private final MediaFileEpisodeRepository mediaFileEpisodeRepository;

    /** All files containing this episode: the direct FK ones plus files found via link rows. */
    public List<MediaFileEntity> filesForEpisode(UUID episodeId) {
        List<MediaFileEntity> result = new ArrayList<>(mediaFileRepository.findByEpisodeEntityId(episodeId));
        Set<UUID> seen = new HashSet<>(result.stream().map(MediaFileEntity::getId).toList());
        for (MediaFileEpisodeEntity link : mediaFileEpisodeRepository.findByEpisodeEntityId(episodeId)) {
            if (seen.add(link.getMediaFileEntityId())) {
                mediaFileRepository.findById(link.getMediaFileEntityId()).ifPresent(result::add);
            }
        }
        return result;
    }

    /** The time slice of this episode within the file; empty for single-episode files. */
    public Optional<MediaFileEpisodeEntity> segmentFor(UUID mediaFileId, UUID episodeId) {
        return mediaFileEpisodeRepository.findByMediaFileEntityIdAndEpisodeEntityId(mediaFileId, episodeId);
    }
}
