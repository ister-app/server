package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class TrackController {
    private final TrackRepository trackRepository;
    private final PersonRepository personRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final LibraryAccessService libraryAccessService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<TrackEntity> trackById(@Argument UUID id, Authentication authentication) {
        return trackRepository.findById(id)
                .filter(track -> libraryAccessService.canAccess(
                        track.getAlbumEntity().getLibraryEntity(), authentication));
    }

    // Since tracks carry their own tag-derived artist, an album's tracks no longer share one
    // person; batch-load to keep album pages at a single person query.
    @BatchMapping(typeName = "Track", field = "artist")
    public Map<TrackEntity, PersonEntity> artist(List<TrackEntity> tracks) {
        List<UUID> personIds = tracks.stream().map(t -> t.getPersonEntity().getId()).distinct().toList();
        Map<UUID, PersonEntity> byId = personRepository.findAllById(personIds).stream()
                .collect(Collectors.toMap(PersonEntity::getId, Function.identity()));
        Map<TrackEntity, PersonEntity> result = new HashMap<>();
        tracks.forEach(t -> result.put(t, byId.get(t.getPersonEntity().getId())));
        return result;
    }

    @BatchMapping(typeName = "Track", field = "playCount")
    public Map<TrackEntity, Integer> playCount(List<TrackEntity> tracks, Authentication authentication) {
        Map<UUID, WatchStatusRepository.TrackPlayStats> stats = playStatsByTrackId(tracks, authentication);
        Map<TrackEntity, Integer> result = new HashMap<>();
        tracks.forEach(t -> result.put(t, Optional.ofNullable(stats.get(t.getId()))
                .map(s -> (int) s.getPlays()).orElse(null)));
        return result;
    }

    @BatchMapping(typeName = "Track", field = "lastPlayedAt")
    public Map<TrackEntity, String> lastPlayedAt(List<TrackEntity> tracks, Authentication authentication) {
        Map<UUID, WatchStatusRepository.TrackPlayStats> stats = playStatsByTrackId(tracks, authentication);
        Map<TrackEntity, String> result = new HashMap<>();
        tracks.forEach(t -> result.put(t, Optional.ofNullable(stats.get(t.getId()))
                .map(s -> s.getLastPlayedAt().toString()).orElse(null)));
        return result;
    }

    private Map<UUID, WatchStatusRepository.TrackPlayStats> playStatsByTrackId(List<TrackEntity> tracks, Authentication authentication) {
        List<UUID> trackIds = tracks.stream().map(TrackEntity::getId).toList();
        return watchStatusRepository.findTrackPlayStats(authentication.getName(), trackIds).stream()
                .collect(Collectors.toMap(WatchStatusRepository.TrackPlayStats::getTrackId, Function.identity()));
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
