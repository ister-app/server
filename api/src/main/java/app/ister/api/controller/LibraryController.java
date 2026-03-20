package app.ister.api.controller;

import app.ister.core.entity.LibraryEntity;
import app.ister.core.repository.LibraryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class LibraryController {
    private final LibraryRepository libraryRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<LibraryEntity> libraries() {
        return libraryRepository.findAll();
    }
}
