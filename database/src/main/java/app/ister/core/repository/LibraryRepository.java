package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LibraryRepository extends JpaRepository<LibraryEntity, UUID> {
    Optional<LibraryEntity> findByName(String name);
}
