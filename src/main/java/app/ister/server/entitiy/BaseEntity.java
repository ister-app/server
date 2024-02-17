package app.ister.server.entitiy;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
//@RequiredArgsConstructor
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Data
public class BaseEntity {

    @Id
    @GeneratedValue(strategy= GenerationType.AUTO)
    private UUID id;

}
