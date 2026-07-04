package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class TrackController {
    private final TrackRepository trackRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<TrackEntity> trackById(@Argument UUID id) {
        return trackRepository.findById(id);
    }

    @SchemaMapping(typeName = "Track", field = "artist")
    public PersonEntity artist(TrackEntity trackEntity) {
        return trackEntity.getPersonEntity();
    }

    @SchemaMapping(typeName = "Track", field = "album")
    public AlbumEntity album(TrackEntity trackEntity) {
        return trackEntity.getAlbumEntity();
    }

    @SchemaMapping(typeName = "Track", field = "metadata")
    public List<MetadataEntity> metadata(TrackEntity trackEntity) {
        return trackEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Track", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(TrackEntity trackEntity) {
        return trackEntity.getMediaFileEntities();
    }
}
