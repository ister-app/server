package app.ister.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication(scanBasePackages = "app.ister")
@ComponentScan(
        basePackages = {"app.ister"}
)
//@SecurityScheme(
//        name = "oidc_auth",
//        type = SecuritySchemeType.OPENIDCONNECT,
//        openIdConnectUrl = "${springdoc.oAuthFlow.openIdConnectUrl}"
//)
public class IsterServerApplication {
    static void main(String[] args) {
        SpringApplication.run(IsterServerApplication.class, args);
    }
}
