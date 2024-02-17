package app.ister.server.repository;

import app.ister.server.entitiy.CategorieEntity;
import org.springframework.data.repository.CrudRepository;

import java.util.UUID;

public interface CatogorieRepository extends CrudRepository<CategorieEntity, UUID> {
}
