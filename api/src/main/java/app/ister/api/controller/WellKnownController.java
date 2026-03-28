package app.ister.api.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WellKnownController {

    private final String clusterName;
    private final String serverUrl;
    private final String openIdConnectUrl;

    public WellKnownController(
            @Value("${app.ister.cluster.name}") String clusterName,
            @Value("${app.ister.server.url}") String serverUrl,
            @Value("${springdoc.oAuthFlow.openIdConnectUrl}") String openIdConnectUrl) {
        this.clusterName = clusterName;
        this.serverUrl = serverUrl;
        this.openIdConnectUrl = openIdConnectUrl;
    }

    @GetMapping(value = "/.well-known/ister", produces = MediaType.TEXT_PLAIN_VALUE)
    public String wellKnown() {
        return clusterName + "\n" + openIdConnectUrl + "\n" + serverUrl;
    }
}
