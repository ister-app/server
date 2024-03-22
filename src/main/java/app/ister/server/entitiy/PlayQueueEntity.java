package app.ister.server.entitiy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.List;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
public class PlayQueueEntity extends BaseEntity {
    @NotNull
    @Convert(converter = ItemsConverter.class)
    @Column(columnDefinition = "jsonb")
    private List<PlayQueueItemEntity> items;
}

class ItemsConverter implements AttributeConverter<List<PlayQueueItemEntity>, String> {
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
            return objectMapper.readValue(value, new TypeReference<List<PlayQueueItemEntity>>(){});
        } catch (JsonProcessingException e) {
            System.out.println("Cannot convert JSON into List<PlaylistItemEntity>");
            return null;
        }
    }
}
