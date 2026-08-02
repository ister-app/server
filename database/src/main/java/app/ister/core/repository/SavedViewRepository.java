package app.ister.core.repository;

import app.ister.core.entity.SavedViewEntity;
import app.ister.core.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SavedViewRepository extends JpaRepository<SavedViewEntity, UUID> {
    List<SavedViewEntity> findByUserEntityOrderByNameAsc(UserEntity userEntity);
}
