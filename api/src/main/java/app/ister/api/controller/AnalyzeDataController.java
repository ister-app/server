package app.ister.api.controller;

import app.ister.core.enums.EventType;
import app.ister.core.eventdata.AnalyzeData;
import app.ister.core.service.MessageSender;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class AnalyzeDataController {

    private final MessageSender messageSender;

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public Boolean analyzeDataForEpisode(@Argument UUID episodeId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .episodeId(episodeId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public Boolean analyzeDataForMovie(@Argument UUID movieId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .movieId(movieId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public Boolean analyzeDataForShow(@Argument UUID showId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .showId(showId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public Boolean analyzeDataForLibrary(@Argument UUID libraryId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .libraryId(libraryId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public Boolean analyzeDataForArtist(@Argument UUID artistId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .artistId(artistId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public Boolean analyzeDataForAlbum(@Argument UUID albumId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .albumId(albumId)
                        .build());
        return true;
    }

    @MutationMapping
    @PreAuthorize("hasRole('user')")
    public Boolean analyzeDataForTrack(@Argument UUID trackId) {
        messageSender.sendAnalyzeData(
                AnalyzeData.builder()
                        .eventType(EventType.ANALYZE_DATA)
                        .trackId(trackId)
                        .build());
        return true;
    }
}
