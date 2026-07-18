package app.ister.core.repository;

import app.ister.core.entity.UserSharingGrantEntity;
import app.ister.core.enums.SharingCapability;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserSharingGrantRepository extends CrudRepository<UserSharingGrantEntity, UUID> {

    List<UserSharingGrantEntity> findByOwnerEntityId(UUID ownerEntityId);

    void deleteByOwnerEntityIdAndCapability(UUID ownerEntityId, SharingCapability capability);

    /** Grantee ids for one owner+capability, read as a projection so no association is navigated
     *  off-session (this is called from the now-playing filter, which runs outside a request tx). */
    @Query("select g.granteeEntity.id from UserSharingGrantEntity g "
            + "where g.ownerEntity.id = :ownerId and g.capability = :capability")
    List<UUID> findGranteeIdsByOwnerAndCapability(@Param("ownerId") UUID ownerId,
                                                  @Param("capability") SharingCapability capability);
}
