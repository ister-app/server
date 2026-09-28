package app.ister.core.storage;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/**
 * A single-node <a href="https://garagehq.deuxfleurs.fr/">Garage</a> as the S3-compatible server of
 * the integration tests (MinIO stopped publishing public images). Garage has no "root user": after
 * start the node gets a layout role and a fixed access key is imported that may create buckets, so
 * a test creates its bucket over S3 exactly as it would against any other server.
 */
public class GarageContainer extends GenericContainer<GarageContainer> {

    public static final String ACCESS_KEY = "GK00000000000000000000000a";
    public static final String SECRET_KEY = "000000000000000000000000000000000000000000000000000000000000000a";
    private static final int S3_PORT = 3900;

    private static final String CONFIG = """
            metadata_dir = "/var/lib/garage/meta"
            data_dir = "/var/lib/garage/data"
            db_engine = "sqlite"
            replication_factor = 1
            rpc_bind_addr = "[::]:3901"
            rpc_public_addr = "127.0.0.1:3901"
            rpc_secret = "0000000000000000000000000000000000000000000000000000000000000000"

            [s3_api]
            s3_region = "us-east-1"
            api_bind_addr = "[::]:3900"
            root_domain = ".s3.garage.localhost"
            """;

    public GarageContainer() {
        super(DockerImageName.parse("docker.io/dxflrs/garage:v2.4.1"));
        withExposedPorts(S3_PORT);
        withCopyToContainer(Transferable.of(CONFIG), "/etc/garage.toml");
        waitingFor(Wait.forLogMessage(".*S3 API server listening.*", 1).withStartupTimeout(Duration.ofMinutes(1)));
    }

    public String getS3URL() {
        return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
    }

    public String getAccessKey() {
        return ACCESS_KEY;
    }

    public String getSecretKey() {
        return SECRET_KEY;
    }

    @Override
    protected void containerIsStarted(com.github.dockerjava.api.command.InspectContainerResponse containerInfo) {
        String nodeId = garage("node", "id", "-q").split("@")[0].trim();
        garage("layout", "assign", "-z", "dc1", "-c", "1G", nodeId);
        garage("layout", "apply", "--version", "1");
        garage("key", "import", "--yes", "-n", "ister-test", ACCESS_KEY, SECRET_KEY);
        garage("key", "allow", "--create-bucket", ACCESS_KEY);
    }

    private String garage(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "/garage";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            ExecResult result = execInContainer(command);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("garage " + String.join(" ", args) + " failed: " + result.getStderr());
            }
            return result.getStdout();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
