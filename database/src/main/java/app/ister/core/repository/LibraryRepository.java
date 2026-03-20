package app.ister.core.repository;

import app.ister.core.entity.LibraryEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface LibraryRepository extends CrudRepository<LibraryEntity, UUID> {
    Optional<LibraryEntity> findByName(String name);
}
