package app.ister.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class UserEntity extends BaseEntity {

    @Column(nullable = false)
    private String externalId;

    private String name;

    private String email;

    /**
     * Snapshot of the Keycloak 'admin' realm role, refreshed on every JWT-authenticated request.
     * Stream-token requests carry no JWT and read this snapshot instead.
     */
    @Setter
    @Column(nullable = false)
    private boolean admin;
}
