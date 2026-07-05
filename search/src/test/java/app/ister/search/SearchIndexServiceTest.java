package app.ister.search;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.search.config.TypesenseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchIndexServiceTest {

    @Mock
    private MovieRepository movieRepository;
    @Mock
    private ShowRepository showRepository;
    @Mock
    private EpisodeRepository episodeRepository;
    @Mock
    private PersonRepository personRepository;
    @Mock
    private AlbumRepository albumRepository;
    @Mock
    private TrackRepository trackRepository;
    @Mock
    private TypesenseClient typesenseClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    private SearchIndexService subject;

    private final MovieEntity movie = movie();

    private static MovieEntity movie() {
        LibraryEntity library = LibraryEntity.builder().name("lib").build();
        library.setId(UUID.randomUUID());
        MovieEntity movie = MovieEntity.builder().name("The Matrix").releaseYear(1999).libraryEntity(library).build();
        movie.setId(UUID.randomUUID());
        return movie;
    }

    @BeforeEach
    void setUp() {
        TypesenseProperties properties = new TypesenseProperties();
        properties.setCollection("media");
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        subject = new SearchIndexService(movieRepository, showRepository, episodeRepository, personRepository,
                albumRepository, trackRepository, new SearchDocumentMapper(), typesenseClient, properties,
                transactionManager);
    }

    @Test
    void upsertIndexesExistingEntity() {
        when(movieRepository.findById(movie.getId())).thenReturn(Optional.of(movie));

        subject.upsert(SearchEntityType.MOVIE, movie.getId());

        verify(typesenseClient).upsertDocument(eq("media"),
                argThat(document -> document.id().equals(movie.getId().toString()) && document.type().equals("MOVIE")));
    }

    @Test
    void upsertOfMissingEntityDeletesDocument() {
        UUID gone = UUID.randomUUID();
        when(movieRepository.findById(gone)).thenReturn(Optional.empty());

        subject.upsert(SearchEntityType.MOVIE, gone);

        verify(typesenseClient).deleteDocument("media", gone.toString());
        verify(typesenseClient, never()).upsertDocument(anyString(), any());
    }

    @Test
    void deleteRemovesDocument() {
        UUID id = UUID.randomUUID();

        subject.delete(id);

        verify(typesenseClient).deleteDocument("media", id.toString());
    }

    @Test
    void reindexBuildsNewCollectionSwapsAliasAndDropsOldOnes() {
        when(movieRepository.findAll(any(PageRequest.class)))
                .thenAnswer(invocation -> page(invocation.getArgument(0), List.of(movie)));
        when(showRepository.findAll(any(PageRequest.class))).thenAnswer(invocation -> Page.empty(invocation.getArgument(0)));
        when(episodeRepository.findAll(any(PageRequest.class))).thenAnswer(invocation -> Page.empty(invocation.getArgument(0)));
        when(personRepository.findAll(any(PageRequest.class))).thenAnswer(invocation -> Page.empty(invocation.getArgument(0)));
        when(albumRepository.findAll(any(PageRequest.class))).thenAnswer(invocation -> Page.empty(invocation.getArgument(0)));
        when(trackRepository.findAll(any(PageRequest.class))).thenAnswer(invocation -> Page.empty(invocation.getArgument(0)));
        when(typesenseClient.listCollectionNames()).thenReturn(List.of("media_v1", "other"));

        subject.reindex();

        verify(typesenseClient).createCollection(startsWith("media_v"));
        verify(typesenseClient).importDocuments(startsWith("media_v"), anyList());
        verify(typesenseClient).upsertAlias(eq("media"), startsWith("media_v"));
        verify(typesenseClient).dropCollection("media_v1");
        verify(typesenseClient, never()).dropCollection("other");
    }

    @Test
    void ensureCollectionCreatesCollectionAndAliasWhenMissing() {
        when(typesenseClient.getAliasTarget("media")).thenReturn(Optional.empty());

        subject.ensureCollection();

        verify(typesenseClient).createCollection(startsWith("media_v"));
        verify(typesenseClient).upsertAlias(eq("media"), startsWith("media_v"));
    }

    @Test
    void ensureCollectionDoesNothingWhenAliasExists() {
        when(typesenseClient.getAliasTarget("media")).thenReturn(Optional.of("media_v1"));

        subject.ensureCollection();

        verify(typesenseClient, never()).createCollection(anyString());
    }

    private static <T> Page<T> page(Pageable pageable, List<T> content) {
        return new PageImpl<>(content, pageable, content.size());
    }
}
