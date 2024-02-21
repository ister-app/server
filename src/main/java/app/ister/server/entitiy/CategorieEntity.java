package app.ister.server.entitiy;

import jakarta.persistence.Entity;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
//@RequiredArgsConstructor
@SuperBuilder
@NoArgsConstructor
@Data
public class CategorieEntity extends BaseEntity {
}
