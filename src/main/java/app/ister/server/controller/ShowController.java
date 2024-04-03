package app.ister.server.controller;

import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.SeasonRepository;
import app.ister.server.repository.ShowRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("shows")
@SecurityRequirement(name = "oidc_auth")
public class ShowController {
    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SeasonRepository seasonRepository;

    @GetMapping(value = "/recent")
    public Page<ShowEntity> getRecent() {
        return showRepository.findAll(PageRequest.of(0, 10, Sort.by("name").descending()));
    }

    @GetMapping(value = "/{id}")
    public Optional<ShowEntity> getTVShow(@PathVariable UUID id) {
        return showRepository.findById(id);
    }

    @GetMapping(value = "/{id}/seasons")
    public List<SeasonEntity> getSeasons(@PathVariable UUID id) {
        return seasonRepository.findByShowEntityId(id, Sort.by("number").ascending());
    }
}
