package app.ister.api.controller;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.Optional;

@Slf4j
@Controller
public class ServerInfoController {

    @Value("${app.ister.server.name}")
    private String serverName;

    @Value("${app.ister.server.url}")
    private String serverUrl;

    @Value("${springdoc.oAuthFlow.openIdConnectUrl}")
    private String openIdConnectUrl;

    @QueryMapping
    public ServerInfo getServerInfo() {
        return ServerInfo.builder()
                .name(serverName)
                .url(serverUrl)
                .version(getImplementationVersion())
                .openIdUrl(openIdConnectUrl)
                .build();
    }

    private String getImplementationVersion() {
        return Optional.ofNullable(getClass().getPackage().getImplementationVersion())
                .orElse("?.?.?-DEVELOPMENT");
    }


    @EqualsAndHashCode
    @Getter
    @Builder
    public static class ServerInfo {
        private String name;
        private String url;
        private String version;
        private String openIdUrl;
    }
}
