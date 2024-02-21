package app.ister.server.entitiy;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Data
@SuperBuilder
public class BaseEntity {

    @Id
    @GeneratedValue(strategy= GenerationType.AUTO)
    private UUID id;

}
