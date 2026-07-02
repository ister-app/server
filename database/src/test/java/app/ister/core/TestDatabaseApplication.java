package app.ister.core;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap: the database module has no @SpringBootApplication of its own,
 * but slice tests like @DataJpaTest need one to anchor component scanning
 * (entities, repositories and PersistenceConfig live under app.ister.core).
 */
@SpringBootApplication
public class TestDatabaseApplication {
}
