package app.ister.server.entitiy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.UUID;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlayQueueEntity extends BaseEntity {

    @NotNull
    @ManyToOne
    private UserEntity userEntity;

    private UUID currentItem;

    @NotNull
    @Convert(converter = ItemsConverter.class)
    @Column(columnDefinition = "text")
    private List<PlayQueueItemEntity> items;

    private static class ItemsConverter implements AttributeConverter<List<PlayQueueItemEntity>, String> {
        private static final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(List<PlayQueueItemEntity> items) {
            try {
                return objectMapper.writeValueAsString(items);
            } catch (JsonProcessingException jpe) {
                System.out.println("Cannot convert List<PlaylistItemEntity> into JSON");
                return null;
            }
        }

        @Override
        public List<PlayQueueItemEntity> convertToEntityAttribute(String value) {
            try {
                return objectMapper.readValue(value, new TypeReference<List<PlayQueueItemEntity>>() {
                });
            } catch (JsonProcessingException e) {
                System.out.println("Cannot convert JSON into List<PlaylistItemEntity>");
                return null;
            }
        }
    }
}
