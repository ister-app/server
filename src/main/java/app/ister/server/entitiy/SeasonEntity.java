package app.ister.server.entitiy;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.*;

@Entity
@RequiredArgsConstructor(access = AccessLevel.PUBLIC)
@NoArgsConstructor
@Data
public class SeasonEntity extends BaseEntity {

    @NonNull
    @ManyToOne
    private ShowEntity showEntity;

    @NonNull
    private int number;
}
