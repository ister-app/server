package app.ister.search;

import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.search.config.TypesenseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Service
@Slf4j
public class SearchIndexService {
    private static final int PAGE_SIZE = 200;

    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;
    private final PersonRepository personRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;
    private final BookRepository bookRepository;
    private final PodcastRepository podcastRepository;
    private final SearchDocumentMapper searchDocumentMapper;
    private final TypesenseClient typesenseClient;
    private final TypesenseProperties properties;
    private final TransactionTemplate readOnlyTransaction;

    @SuppressWarnings("java:S107") // one dependency per searchable entity type
    public SearchIndexService(MovieRepository movieRepository,
                              ShowRepository showRepository,
                              EpisodeRepository episodeRepository,
                              PersonRepository personRepository,
                              AlbumRepository albumRepository,
                              TrackRepository trackRepository,
                              BookRepository bookRepository,
                              PodcastRepository podcastRepository,
                              SearchDocumentMapper searchDocumentMapper,
                              TypesenseClient typesenseClient,
                              TypesenseProperties properties,
                              PlatformTransactionManager transactionManager) {
        this.movieRepository = movieRepository;
        this.showRepository = showRepository;
        this.episodeRepository = episodeRepository;
        this.personRepository = personRepository;
        this.albumRepository = albumRepository;
        this.trackRepository = trackRepository;
        this.bookRepository = bookRepository;
        this.podcastRepository = podcastRepository;
        this.searchDocumentMapper = searchDocumentMapper;
        this.typesenseClient = typesenseClient;
        this.properties = properties;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Transactional(readOnly = true)
    public void upsert(SearchEntityType entityType, UUID entityId) {
        Optional<SearchDocument> document = loadDocument(entityType, entityId);
        if (document.isPresent()) {
            typesenseClient.upsertDocument(properties.getCollection(), document.get());
        } else {
            // Entity is gone (e.g. deleted between event and handling): remove any stale document.
            typesenseClient.deleteDocument(properties.getCollection(), entityId.toString());
        }
    }

    public void delete(UUID entityId) {
        typesenseClient.deleteDocument(properties.getCollection(), entityId.toString());
    }

    /**
     * Rebuilds the index into a fresh collection and swaps the alias, so searches keep working
     * during the rebuild. Old collections are dropped afterwards.
     */
    public void reindex() {
        String newCollection = properties.getCollection() + "_v" + System.currentTimeMillis();
        log.info("Reindexing search into collection {}", newCollection);
        typesenseClient.createCollection(newCollection);
        indexAll(movieRepository, searchDocumentMapper::toDocument, newCollection);
        indexAll(showRepository, searchDocumentMapper::toDocument, newCollection);
        indexAll(episodeRepository, searchDocumentMapper::toDocument, newCollection);
        indexAll(personRepository, searchDocumentMapper::toDocument, newCollection);
        indexAll(albumRepository, searchDocumentMapper::toDocument, newCollection);
        indexAll(trackRepository, searchDocumentMapper::toDocument, newCollection);
        indexAll(bookRepository, searchDocumentMapper::toDocument, newCollection);
        indexAll(podcastRepository, searchDocumentMapper::toDocument, newCollection);
        typesenseClient.upsertAlias(properties.getCollection(), newCollection);
        dropOldCollections(newCollection);
        log.info("Search reindex finished; alias {} now points to {}", properties.getCollection(), newCollection);
    }

    /** Creates an empty collection plus alias when none exists yet, so single upserts don't 404. */
    public void ensureCollection() {
        if (typesenseClient.getAliasTarget(properties.getCollection()).isEmpty()) {
            String newCollection = properties.getCollection() + "_v" + System.currentTimeMillis();
            typesenseClient.createCollection(newCollection);
            typesenseClient.upsertAlias(properties.getCollection(), newCollection);
            log.info("Created search collection {} with alias {}", newCollection, properties.getCollection());
        }
    }

    private Optional<SearchDocument> loadDocument(SearchEntityType entityType, UUID entityId) {
        return switch (entityType) {
            case MOVIE -> movieRepository.findById(entityId).map(searchDocumentMapper::toDocument);
            case SHOW -> showRepository.findById(entityId).map(searchDocumentMapper::toDocument);
            case EPISODE -> episodeRepository.findById(entityId).map(searchDocumentMapper::toDocument);
            case PERSON -> personRepository.findById(entityId).map(searchDocumentMapper::toDocument);
            case ALBUM -> albumRepository.findById(entityId).map(searchDocumentMapper::toDocument);
            case TRACK -> trackRepository.findById(entityId).map(searchDocumentMapper::toDocument);
            case BOOK -> bookRepository.findById(entityId).map(searchDocumentMapper::toDocument);
            case PODCAST -> podcastRepository.findById(entityId).map(searchDocumentMapper::toDocument);
        };
    }

    /** Pages a repository into the collection; one read-only transaction per page. */
    private <T> void indexAll(JpaRepository<T, UUID> repository,
                              Function<T, SearchDocument> toDocument,
                              String collection) {
        int page = 0;
        boolean hasNext = true;
        while (hasNext) {
            final int currentPage = page;
            PageResult result = readOnlyTransaction.execute(status -> {
                Page<T> entities = repository.findAll(PageRequest.of(currentPage, PAGE_SIZE));
                return new PageResult(entities.map(toDocument).toList(), entities.hasNext());
            });
            if (!result.documents().isEmpty()) {
                typesenseClient.importDocuments(collection, result.documents());
            }
            hasNext = result.hasNext();
            page++;
        }
    }

    private void dropOldCollections(String currentCollection) {
        String prefix = properties.getCollection() + "_v";
        typesenseClient.listCollectionNames().stream()
                .filter(name -> name.startsWith(prefix) && !name.equals(currentCollection))
                .forEach(typesenseClient::dropCollection);
    }

    private record PageResult(List<SearchDocument> documents, boolean hasNext) {
    }
}
