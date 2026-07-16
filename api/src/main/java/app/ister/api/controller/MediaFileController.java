package app.ister.api.controller;

import app.ister.core.entity.MediaFileEntity;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

@Controller
public class MediaFileController {

    /**
     * The file format from the path extension, uppercased. Lets clients pick a reader
     * (epub vs cbz vs pdf) without sniffing extensions out of {@code path} themselves.
     */
    @SchemaMapping(typeName = "MediaFile", field = "format")
    public String format(MediaFileEntity mediaFile) {
        String path = mediaFile.getPath();
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1 || path.lastIndexOf('/') > dot) {
            return null;
        }
        return path.substring(dot + 1).toUpperCase();
    }
}
