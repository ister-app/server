package app.ister.api.controller;

import app.ister.core.entity.CreditEntity;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditControllerTest {

    @InjectMocks
    private CreditController subject;

    @Mock
    private CreditRepository creditRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private ShowRepository showRepository;

    @Mock
    private EpisodeRepository episodeRepository;

    @Test
    void castByShowIdPagesFromShowRepository() {
        UUID showId = UUID.randomUUID();
        Page<CreditEntity> page = new PageImpl<>(List.of(CreditEntity.builder().characterName("Neo").build()));
        when(creditRepository.findByShowEntityId(eq(showId), any(Pageable.class))).thenReturn(page);

        Page<CreditEntity> result = subject.cast(
                Optional.of(showId), Optional.empty(), Optional.empty(),
                Optional.of(0), Optional.of(10));

        assertSame(page, result);
        verify(creditRepository, never()).findByMovieEntityId(any(), any());
        verify(creditRepository, never()).findByEpisodeEntityId(any(), any());
    }

    @Test
    void castByMovieIdPagesFromMovieRepository() {
        UUID movieId = UUID.randomUUID();
        Page<CreditEntity> page = new PageImpl<>(List.of(CreditEntity.builder().build()));
        when(creditRepository.findByMovieEntityId(eq(movieId), any(Pageable.class))).thenReturn(page);

        Page<CreditEntity> result = subject.cast(
                Optional.empty(), Optional.of(movieId), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertSame(page, result);
    }

    @Test
    void castByEpisodeIdPagesFromEpisodeRepository() {
        UUID episodeId = UUID.randomUUID();
        Page<CreditEntity> page = new PageImpl<>(List.of(CreditEntity.builder().build()));
        when(creditRepository.findByEpisodeEntityId(eq(episodeId), any(Pageable.class))).thenReturn(page);

        Page<CreditEntity> result = subject.cast(
                Optional.empty(), Optional.empty(), Optional.of(episodeId),
                Optional.empty(), Optional.empty());

        assertSame(page, result);
    }

    @Test
    void castSortsByCastOrderNullsLast() {
        UUID showId = UUID.randomUUID();
        when(creditRepository.findByShowEntityId(eq(showId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        subject.cast(Optional.of(showId), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(creditRepository).findByShowEntityId(eq(showId), captor.capture());
        Sort.Order order = captor.getValue().getSort().getOrderFor("castOrder");
        assertEquals(Sort.Direction.ASC, order.getDirection());
        assertEquals(Sort.NullHandling.NULLS_LAST, order.getNullHandling());
    }

    @Test
    void castDefaultsPageAndSizeWhenAbsent() {
        UUID showId = UUID.randomUUID();
        when(creditRepository.findByShowEntityId(eq(showId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        subject.cast(Optional.of(showId), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(creditRepository).findByShowEntityId(eq(showId), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(20, captor.getValue().getPageSize());
    }

    @Test
    void castClampsSizeToMaxPageSize() {
        UUID showId = UUID.randomUUID();
        when(creditRepository.findByShowEntityId(eq(showId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        subject.cast(Optional.of(showId), Optional.empty(), Optional.empty(),
                Optional.of(0), Optional.of(10_000));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(creditRepository).findByShowEntityId(eq(showId), captor.capture());
        assertEquals(Paging.MAX_PAGE_SIZE, captor.getValue().getPageSize());
    }

    @Test
    void castThrowsWhenNoIdProvided() {
        assertThrows(IllegalArgumentException.class, () -> subject.cast(
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty()));
        verifyNoInteractions(creditRepository);
    }

    @Test
    void castThrowsWhenMultipleIdsProvided() {
        Optional<UUID> movieId = Optional.of(UUID.randomUUID());
        Optional<UUID> showId = Optional.of(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class, () -> subject.cast(
                movieId, showId, Optional.empty(), Optional.empty(), Optional.empty()));
        verifyNoInteractions(creditRepository);
    }
}
