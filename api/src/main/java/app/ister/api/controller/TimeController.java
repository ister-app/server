package app.ister.api.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Server clock for client clock-offset measurement (listen-along tight sync).
 * Clients probe this in short RTT bursts and take the median, so the endpoint
 * must stay as light as possible: no auth, no database, just the clock. It is
 * deliberately public (see OIDCSecurityConfig) — it reveals nothing beyond the
 * wall-clock time. In a cluster every node must be NTP-disciplined, or the
 * offset a client measures depends on which node answered.
 */
@RestController
public class TimeController {

    @GetMapping(value = "/time", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Long> time() {
        return Map.of("serverTimeMs", System.currentTimeMillis());
    }
}
