package app.ister.server.controller;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.SeasonRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("season")
public class SeasonController {
    @Autowired
    private SeasonRepository seasonRepository;

    @Autowired
    private EpisodeRepository episodeRepository;

    @GetMapping(value = "/{id}")
    public Optional<SeasonEntity> get(@PathVariable UUID id) {
        return seasonRepository.findById(id);
    }

    @GetMapping(value = "/{id}/episodes")
    public List<EpisodeEntity> getEpisodes(@PathVariable UUID id) {
        return episodeRepository.findBySeasonEntityIdOrderByNumberAsc(id);
    }
}
