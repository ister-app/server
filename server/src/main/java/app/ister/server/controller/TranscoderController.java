package app.ister.server.controller;

import app.ister.server.Transcoder;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.TranscodeSessionEntity;
import app.ister.server.enums.StreamCodecType;
import app.ister.server.repository.MediaFileRepository;
import app.ister.server.repository.MediaFileStreamRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("transcode")
@Slf4j
@SecurityRequirement(name = "oidc_auth")
public class TranscoderController {
    @Autowired
    private MediaFileRepository mediaFileRepository;
    @Autowired
    private MediaFileStreamRepository mediaFileStreamRepository;

    @Value("${app.ister.server.tmp-dir}")
    private String tmpDir;

    @Value("${app.ister.server.ffmpeg-dir}")
    private String dirOfFFmpeg;

    private ArrayList<TranscodeSessionEntity> transcodeSessionEntities = new ArrayList<>();

    @RequestMapping(value = "/download/{id}/{fileName}", method = RequestMethod.GET)
    public InputStreamResource download(@PathVariable UUID id, @PathVariable String fileName) throws IOException {
        String filePath = tmpDir + id + "/" + fileName;
        return new InputStreamResource(new FileInputStream(filePath)) {
            @Override
            public long contentLength() throws IOException {
                return Files.size(Paths.get(filePath));
            }
        };
    }

    @RequestMapping(value = "/stop/{id}", method = RequestMethod.GET)
    public void stop(@PathVariable UUID id) {
        log.debug("Stopping: {}", id);
        getSesion(id).orElseThrow().getTranscoder().stop();
    }

    @RequestMapping(value = "/ready/{id}", method = RequestMethod.GET)
    public boolean ready(@PathVariable UUID id) {
        log.debug("Ready check: {}", id);
        var result = false;
        if (transcodeSessionEntities != null) {
            result = getSesion(id).orElseThrow().getTranscoder().ready();
        }
        return  result;
    }

    @RequestMapping(value = "/start", method = RequestMethod.POST)
    public UUID start(@RequestParam UUID mediaFileId, @RequestParam int startTimeInSeconds, @RequestParam Optional<UUID> audioId, @RequestParam Optional<UUID> subtitleId) throws IOException {
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
