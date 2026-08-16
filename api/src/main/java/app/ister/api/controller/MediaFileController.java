package app.ister.api.controller;

import app.ister.api.dto.MediaSegment;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileSegmentEntity;
import app.ister.core.repository.MediaFileSegmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class MediaFileController {

    private final MediaFileSegmentRepository mediaFileSegmentRepository;

    /**
     * The file format from the path extension, uppercased. Lets clients pick a reader
     * (epub vs cbz vs pdf) without sniffing extensions out of {@code path} themselves.
     */
    @SchemaMapping(typeName = "MediaFile", field = "format")
    public String format(MediaFileEntity mediaFile) {
        String path = mediaFile.getPath();
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1 || path.lastIndexOf('/') > dot) {
            return null;
        }
        return path.substring(dot + 1).toUpperCase();
    }

    @BatchMapping(typeName = "MediaFile", field = "segments")
    public Map<MediaFileEntity, List<MediaSegment>> segments(List<MediaFileEntity> mediaFiles) {
        Map<UUID, List<MediaFileSegmentEntity>> byFileId = mediaFileSegmentRepository
                .findByMediaFileEntityIdIn(mediaFiles.stream().map(MediaFileEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(MediaFileSegmentEntity::getMediaFileEntityId));
        return mediaFiles.stream().collect(Collectors.toMap(file -> file,
                file -> byFileId.getOrDefault(file.getId(), List.of()).stream()
                        .map(s -> new MediaSegment(s.getId(), s.getType(),
                                s.getStartInMilliseconds(), s.getEndInMilliseconds(), s.getEpisodeEntityId()))
                        .toList()));
    }
}
