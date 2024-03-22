package app.ister.server.controller;

import app.ister.server.entitiy.PlayQueueEntity;
import app.ister.server.entitiy.PlayQueueItemEntity;
import app.ister.server.repository.EpisodeRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("playqueue")
@SecurityRequirement(name = "oidc_auth")
public class PlayQueueController {
    @Autowired
    private EpisodeRepository episodeRepository;

    @PostMapping(value = "/create/show/{showId}")
    public PlayQueueEntity createNewForShow(@PathVariable UUID showId) {
        List<PlayQueueItemEntity> items = episodeRepository.findByShowEntityId(
                        UUID.fromString(showId.toString()),
                        Sort.by("SeasonEntityNumber").ascending().and(
                                Sort.by("number").ascending())).stream()
                .map(idOnly -> PlayQueueItemEntity.builder().id(UUID.randomUUID()).itemId(idOnly.getId()).build()).collect(Collectors.toList());
        return PlayQueueEntity.builder().items(items).build();
    }
}
