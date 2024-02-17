package app.ister.server.controller;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.repository.EpisodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("episodes")
public class EpisodeController {
    @Autowired
    private EpisodeRepository episodeRepository;

    @GetMapping(value = "/recent")
    public Page<EpisodeEntity> getRecent() {
        return episodeRepository.findAll(PageRequest.of(0, 3, Sort.by("number").descending()));
    }

    @GetMapping(value = "/{id}")
    public Optional<EpisodeEntity> getEpisode(@PathVariable UUID id) {
        return episodeRepository.findById(id);
    }
}
