package app.ister.server.entitiy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
}
