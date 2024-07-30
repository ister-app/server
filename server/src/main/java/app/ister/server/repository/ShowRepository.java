package app.ister.server.repository;

import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.ShowEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
    Optional<ShowEntity> findByLibraryEntityAndNameAndReleaseYear(LibraryEntity libraryEntity, String name, int releaseYear);
}
