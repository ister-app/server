package app.ister.disk.config;

import app.ister.core.enums.LibraryType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LibraryConfigClass {
    private String name;
    private LibraryType type;
}
