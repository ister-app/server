package app.ister.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class StreamTokenEntity extends BaseEntity {

    @ManyToOne(optional = false)
    private UserEntity userEntity;

    @Column(nullable = false, unique = true)
    private UUID token;

    @Column(nullable = false)
    private Instant expiresAt;
}
