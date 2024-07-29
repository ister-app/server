package app.ister.server.repository;

import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.MovieEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends CrudRepository<MovieEntity, UUID> {
    Optional<MovieEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);

    Page<MovieEntity> findAll(Pageable pageable);
}
