package app.ister.server.repository;

import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.MovieEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MovieRepository extends JpaRepository<MovieEntity, UUID> {
    Optional<MovieEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);
}
