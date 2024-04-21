package app.ister.server.controller;

import app.ister.server.Transcoder;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.TranscodeSessionEntity;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Controller
public class TranscoderController {
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    private final ArrayList<TranscodeSessionEntity> transcodeSessionEntities = new ArrayList<>();

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean stopTranscoding(@Argument UUID id) {
        log.debug("Stopping: {}", id);
        getSesion(id).orElseThrow().getTranscoder().stop();
        return true;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean readyTranscoding(@Argument UUID id) {
        log.debug("Ready check: {}", id);
        var result = false;
        if (!transcodeSessionEntities.isEmpty()) {
            result = getSesion(id).orElseThrow().getTranscoder().ready();
        }
        return  result;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public UUID startTranscoding(@Argument UUID mediaFileId, @Argument int startTimeInSeconds, @Argument Optional<UUID> audioId, @Argument Optional<UUID> subtitleId) throws IOException {
        var mediaFile = mediaFileRepository.findById(mediaFileId).orElseThrow();

        // Set the optional subtitleMediaFileStream.
        Optional<MediaFileStreamEntity> subtitleMediaFileStream = Optional.empty();
        if (subtitleId.isPresent()) {
            subtitleMediaFileStream = mediaFileStreamRepository.findById(subtitleId.get());
        }

        // Set the audio index
        Integer audioIndex = null;
        if (audioId.isPresent()) {
            var audioMediaFileStream = mediaFileStreamRepository.findById(audioId.get());
            if (audioMediaFileStream.isPresent()) {
                audioIndex = audioMediaFileStream.get().getStreamIndex();
            }

        }
        if (audioId.isEmpty() || audioIndex == null) {
            var firstAudioStream = mediaFile.getMediaFileStreamEntity().stream().filter(mediaFileStream -> mediaFileStream.getCodecType().equals(StreamCodecType.AUDIO)).findFirst();
            if (firstAudioStream.isPresent()) {
                audioIndex = firstAudioStream.get().getStreamIndex();
            }
        }
        var transcodeSession = createSession();
        log.debug("Starting: {} for mediafile: {}", transcodeSession.getId(), mediaFileId);
        transcodeSession.getTranscoder().start(mediaFile.getPath(), transcodeSession.getDir(), startTimeInSeconds, audioIndex, subtitleMediaFileStream);
        return transcodeSession.getId();
    }

    private TranscodeSessionEntity createSession() throws IOException {
        TranscodeSessionEntity transcodeSessionEntity = new TranscodeSessionEntity();
        transcodeSessionEntity.setTranscoder(new Transcoder(dirOfFFmpeg));
        transcodeSessionEntity.setId(UUID.randomUUID());
        transcodeSessionEntity.setDir(tmpDir + transcodeSessionEntity.getId() + "/");
        Files.createDirectories(Paths.get(tmpDir + transcodeSessionEntity.getId()));
        transcodeSessionEntities.add(transcodeSessionEntity);
        return transcodeSessionEntity;
    }

    private Optional<TranscodeSessionEntity> getSesion(UUID id) {
        return transcodeSessionEntities.stream().filter(transcodeSessionEntity -> transcodeSessionEntity.getId().equals(id)).findFirst();
    }
}
