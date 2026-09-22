package app.ister.core.service;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.PlayQueueItemEntity;
import app.ister.core.entity.PlaylistItemEntity;
import app.ister.core.entity.RatingEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.PlayQueueItemRepository;
import app.ister.core.repository.PlaylistItemRepository;
import app.ister.core.repository.RatingRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * After a scan swept away media files, the tracks (and albums) they belonged to would otherwise
 * linger as empty rows: the album shows up twice, once without playable tracks. A moved or
 * renamed file comes back as a <em>new</em> track, so before an orphan is deleted its listening
 * history (watch status, play-queue and playlist items, rating) is handed over to the track by
 * the same artist with the same title that still has a file, if there is one. Artists nothing
 * refers to any more go too: they would otherwise stay in the library's artist list.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrphanTrackCleanupService {
    private final TrackRepository trackRepository;
    private final AlbumRepository albumRepository;
    private final MetadataRepository metadataRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final PlayQueueItemRepository playQueueItemRepository;
    private final PlaylistItemRepository playlistItemRepository;
    private final RatingRepository ratingRepository;
    private final PersonRepository personRepository;
    private final ServerEventService serverEventService;

    /** @return the number of orphaned tracks removed */
    @Transactional
    public int cleanUp(LibraryEntity library) {
        if (library == null || library.getLibraryType() != LibraryType.MUSIC) {
            return 0;
        }
        List<TrackEntity> orphans = trackRepository.findOrphansInLibrary(library.getId());
        for (TrackEntity orphan : orphans) {
            Optional<TrackEntity> replacement = findReplacement(orphan);
            replacement.ifPresentOrElse(
                    target -> log.info("Track {} lost its media file; moving its history to {} and deleting it", orphan.getId(), target.getId()),
                    () -> log.info("Track {} lost its media file and has no successor; deleting it", orphan.getId()));
            moveHistory(orphan, replacement.orElse(null));
            trackRepository.delete(orphan);
            serverEventService.createSearchDeleteEvent(SearchEntityType.TRACK, orphan.getId());
        }
        List<AlbumEntity> emptyAlbums = albumRepository.findEmptyInLibrary(library.getId());
        for (AlbumEntity album : emptyAlbums) {
            log.info("Album {} ({}) has no tracks left; deleting it", album.getId(), album.getName());
            ratingRepository.deleteAll(ratingRepository.findByAlbumEntity(album));
            albumRepository.delete(album);
            serverEventService.createSearchDeleteEvent(SearchEntityType.ALBUM, album.getId());
        }
        // Track credits are rewritten by the analysis that follows a scan, so an artist emptied
        // there (a split "A & B" credit) is only seen here on the next scan.
        for (PersonEntity person : personRepository.findUnusedInMusicLibrary(library.getId())) {
            log.info("Artist {} ({}) has no albums, tracks or credits left; deleting it", person.getId(), person.getName());
            personRepository.delete(person);
            serverEventService.createSearchDeleteEvent(SearchEntityType.PERSON, person.getId());
        }
        return orphans.size();
    }

    private Optional<TrackEntity> findReplacement(TrackEntity orphan) {
        Set<String> titles = new LinkedHashSet<>();
        for (MetadataEntity metadata : metadataRepository.findByTrackEntityId(orphan.getId())) {
            if (metadata.getTitle() != null && !metadata.getTitle().isBlank()) {
                titles.add(metadata.getTitle());
            }
        }
        for (String title : titles) {
            List<TrackEntity> candidates = trackRepository.findReplacementCandidates(orphan.getPersonEntity(), title, orphan.getId());
            if (!candidates.isEmpty()) {
                return Optional.of(candidates.getFirst());
            }
        }
        return Optional.empty();
    }

    /** Everything a user attached to the orphan moves to {@code target}, or goes when there is none. */
    private void moveHistory(TrackEntity orphan, TrackEntity target) {
        moveWatchStatuses(orphan, target);
        moveRatings(orphan, target);
        moveQueueAndPlaylistItems(orphan, target);
    }

    private void moveWatchStatuses(TrackEntity orphan, TrackEntity target) {
        for (WatchStatusEntity status : watchStatusRepository.findByTrackEntity(orphan)) {
            boolean targetHasOne = target != null && watchStatusRepository
                    .findByUserEntityAndPlayQueueItemIdAndTrackEntity(status.getUserEntity(), status.getPlayQueueItemId(), target)
                    .isPresent();
            if (target == null || targetHasOne) {
                watchStatusRepository.delete(status);
            } else {
                status.setTrackEntity(target);
                watchStatusRepository.save(status);
            }
        }
    }

    private void moveRatings(TrackEntity orphan, TrackEntity target) {
        for (RatingEntity rating : ratingRepository.findByTrackEntity(orphan)) {
            boolean targetHasOne = target != null
                    && ratingRepository.findByUserEntityAndTrackEntity(rating.getUserEntity(), target).isPresent();
            if (target == null || targetHasOne) {
                ratingRepository.delete(rating);
            } else {
                rating.setTrackEntity(target);
                ratingRepository.save(rating);
            }
        }
    }

    private void moveQueueAndPlaylistItems(TrackEntity orphan, TrackEntity target) {
        for (PlayQueueItemEntity item : playQueueItemRepository.findByTrackEntityId(orphan.getId())) {
            if (target == null) {
                playQueueItemRepository.delete(item);
            } else {
                item.setTrackEntityId(target.getId());
                playQueueItemRepository.save(item);
            }
        }
        for (PlaylistItemEntity item : playlistItemRepository.findByTrackEntityId(orphan.getId())) {
            if (target == null) {
                playlistItemRepository.delete(item);
            } else {
                item.setTrackEntityId(target.getId());
                playlistItemRepository.save(item);
            }
        }
    }
}
