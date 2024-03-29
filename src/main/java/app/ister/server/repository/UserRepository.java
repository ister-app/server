package app.ister.server.repository;

import app.ister.server.entitiy.ShowEntity;
import app.ister.server.entitiy.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends CrudRepository<UserEntity, UUID> {
    Optional<UserEntity> findByExternalId(String externalId);
}
