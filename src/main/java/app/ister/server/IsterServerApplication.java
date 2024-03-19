package app.ister.server;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "app.ister.server")
@SecurityScheme(
		name = "oidc_auth",
		type = SecuritySchemeType.OPENIDCONNECT,
		openIdConnectUrl = "${springdoc.oAuthFlow.openIdConnectUrl}"
)
public class IsterServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(IsterServerApplication.class, args);
	}

}
