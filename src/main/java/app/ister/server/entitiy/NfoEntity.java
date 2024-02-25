package app.ister.server.entitiy;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.SuperBuilder;

import java.sql.Timestamp;

@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class NfoEntity extends BaseEntity {
    @NonNull
    @ManyToOne
    private DiskEntity diskEntity;

    @NonNull
    private String path;
}
