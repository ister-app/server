package app.ister.server.config;

import app.ister.server.enums.LibraryType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LibraryConfigClass {
    private String name;
    private LibraryType type;
}
