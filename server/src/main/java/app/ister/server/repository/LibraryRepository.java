package app.ister.server.repository;

import app.ister.server.entitiy.LibraryEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface LibraryRepository extends CrudRepository<LibraryEntity, UUID> {
    Optional<LibraryEntity> findByName(String name);
}
