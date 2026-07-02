package app.ister.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap: the api module has no @SpringBootApplication of its own, but slice
 * tests like @GraphQlTest need one to anchor component scanning of the controllers.
 */
@SpringBootApplication
public class TestApiApplication {
}
