package app.ister.api.controller;

import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.WatchStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MovieControllerTest {

    @InjectMocks
    private MovieController subject;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private WatchStatusRepository watchStatusRepository;

    @Mock
    private Authentication authentication;

    @Test
    void moviesRecentAddedReturnsSortedList() {
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).build();
        when(movieRepository.findAll(any(Sort.class))).thenReturn(List.of(movie));

        List<MovieEntity> result = subject.moviesRecentAdded();

        assertEquals(1, result.size());
        assertEquals(movie, result.get(0));
    }

    @Test
    void movieByIdReturnsFromRepository() {
        UUID id = UUID.randomUUID();
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).build();
        when(movieRepository.findById(id)).thenReturn(Optional.of(movie));

        Optional<MovieEntity> result = subject.movieById(id);

        assertTrue(result.isPresent());
        assertEquals(movie, result.get());
    }

    @Test
    void movieByIdReturnsEmptyWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(movieRepository.findById(id)).thenReturn(Optional.empty());

        Optional<MovieEntity> result = subject.movieById(id);

        assertTrue(result.isEmpty());
    }

    @Test
    void metadataReturnsMovieMetadata() {
        MetadataEntity meta = MetadataEntity.builder().build();
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).metadataEntities(List.of(meta)).build();

        List<MetadataEntity> result = subject.metadata(movie);

        assertEquals(1, result.size());
        assertEquals(meta, result.get(0));
    }

    @Test
    void imagesReturnsMovieImages() {
        ImageEntity image = ImageEntity.builder().build();
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).imagesEntities(List.of(image)).build();

        List<ImageEntity> result = subject.images(movie);

        assertEquals(1, result.size());
    }

    @Test
    void watchStatusReturnsWatchStatusForUser() {
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).build();
        WatchStatusEntity ws = WatchStatusEntity.builder().watched(false).build();
        when(authentication.getName()).thenReturn("user1");
        when(watchStatusRepository.findByUserEntityExternalIdAndMovieEntity(eq("user1"), eq(movie), any(Sort.class)))
                .thenReturn(List.of(ws));

        List<WatchStatusEntity> result = subject.watchStatus(movie, authentication);

        assertEquals(1, result.size());
    }

    @Test
    void mediaFileReturnsMovieMediaFiles() {
        MediaFileEntity mediaFile = MediaFileEntity.builder().build();
        MovieEntity movie = MovieEntity.builder().name("Test").releaseYear(2020).mediaFileEntities(List.of(mediaFile)).build();

        List<MediaFileEntity> result = subject.mediaFile(movie);

        assertEquals(1, result.size());
    }
}
