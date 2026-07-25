package app.ister.core.repository;

import app.ister.core.entity.CreditEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.PagingAndSortingRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CreditRepository extends CrudRepository<CreditEntity, UUID>, PagingAndSortingRepository<CreditEntity, UUID> {
    void deleteByMovieEntityId(UUID movieEntityId);

    void deleteByShowEntityId(UUID showEntityId);

    void deleteByEpisodeEntityId(UUID episodeEntityId);

    List<CreditEntity> findByMovieEntityIdIn(Collection<UUID> movieEntityIds);

    List<CreditEntity> findByShowEntityIdIn(Collection<UUID> showEntityIds);

    List<CreditEntity> findByEpisodeEntityIdIn(Collection<UUID> episodeEntityIds);

    List<CreditEntity> findByPersonEntityId(UUID personEntityId, Sort sort);

    Page<CreditEntity> findByMovieEntityId(UUID movieEntityId, Pageable pageable);

    Page<CreditEntity> findByShowEntityId(UUID showEntityId, Pageable pageable);

    Page<CreditEntity> findByEpisodeEntityId(UUID episodeEntityId, Pageable pageable);

    /**
     * True when the person has at least one credit whose movie, show or episode lies in one of the
     * given libraries. Never call with an empty collection (empty in-lists are dialect-dependent).
     */
    @Query("""
            select count(c) > 0
            from CreditEntity c
            left join c.movieEntity m
            left join c.showEntity s
            left join c.episodeEntity e
            left join e.showEntity es
            where c.personEntity.id = :personEntityId
              and (m.libraryEntity.id in :libraryIds
                or s.libraryEntity.id in :libraryIds
                or es.libraryEntity.id in :libraryIds)
            """)
    boolean hasCreditInLibraries(UUID personEntityId, Collection<UUID> libraryIds);

    /**
     * The person's credits restricted to the given libraries, castOrder ascending (mirrors
     * {@link #findByPersonEntityId}). Never call with an empty collection.
     */
    @Query("""
            select c
            from CreditEntity c
            left join c.movieEntity m
            left join c.showEntity s
            left join c.episodeEntity e
            left join e.showEntity es
            where c.personEntity.id = :personEntityId
              and (m.libraryEntity.id in :libraryIds
                or s.libraryEntity.id in :libraryIds
                or es.libraryEntity.id in :libraryIds)
            order by c.castOrder
            """)
    List<CreditEntity> findByPersonEntityIdInLibraries(UUID personEntityId, Collection<UUID> libraryIds);
}
