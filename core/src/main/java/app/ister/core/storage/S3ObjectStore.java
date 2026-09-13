package app.ister.core.storage;

import app.ister.core.config.S3Properties;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link ObjectStore} on the AWS SDK v2 synchronous client. Built once per configured connection by
 * {@link ObjectStoreRegistry}. Works against AWS (no endpoint) and any S3-compatible server
 * (endpoint override + path-style; flexible checksums are only sent when the server requires them,
 * which keeps MinIO/Garage/Ceph happy).
 */
@Slf4j
public class S3ObjectStore implements ObjectStore {

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final String name;

    public S3ObjectStore(S3Properties.Connection connection) {
        this.name = connection.getName();
        this.bucket = connection.getBucket();
        AwsCredentialsProvider credentials = credentials(connection);
        Region region = Region.of(connection.getRegion());
        S3Configuration serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(connection.isPathStyle())
                .build();

        S3ClientBuilder builder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(10))
                        .socketTimeout(Duration.ofMinutes(5)));
        S3Presigner.Builder presignerBuilder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        if (!connection.isAws()) {
            URI endpoint = URI.create(connection.getEndpoint());
            builder.endpointOverride(endpoint)
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
            presignerBuilder.endpointOverride(endpoint);
        }
        this.client = builder.build();
        this.presigner = presignerBuilder.build();
    }

    /** Test seam. */
    S3ObjectStore(S3Client client, S3Presigner presigner, String bucket, String name) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.name = name;
    }

    private static AwsCredentialsProvider credentials(S3Properties.Connection c) {
        if (c.getAccessKey() == null || c.getAccessKey().isBlank()) {
            // AWS: instance profile / env / ~/.aws; the usual chain
            return DefaultCredentialsProvider.builder().build();
        }
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(c.getAccessKey(), c.getSecretKey()));
    }

    public String name() {
        return name;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public Stream<ObjectStat> list(String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();
        return client.listObjectsV2Paginator(request).contents().stream().map(S3ObjectStore::toStat);
    }

    @Override
    public Listing listShallow(String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .delimiter("/")
                .build();
        List<ObjectStat> objects = new java.util.ArrayList<>();
        List<String> children = new java.util.ArrayList<>();
        for (ListObjectsV2Response page : client.listObjectsV2Paginator(request)) {
            page.contents().stream()
                    // the "directory marker" object some tools create for the prefix itself
                    .filter(o -> !o.key().equals(prefix))
                    .map(S3ObjectStore::toStat)
                    .forEach(objects::add);
            page.commonPrefixes().forEach(cp -> {
                String p = cp.prefix();
                children.add(p.endsWith("/") ? p.substring(0, p.length() - 1) : p);
            });
        }
        return new Listing(objects, children);
    }

    private static ObjectStat toStat(S3Object o) {
        return new ObjectStat(o.key(), o.size() == null ? 0 : o.size(), o.lastModified(), o.eTag(), null);
    }

    @Override
    public Optional<ObjectStat> stat(String key) {
        try {
            HeadObjectResponse head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new ObjectStat(key, head.contentLength() == null ? 0 : head.contentLength(),
                    head.lastModified(), head.eTag(), head.contentType()));
        } catch (NoSuchKeyException _) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new java.nio.file.NoSuchFileException(uri(key));
        } catch (SdkException e) {
            throw new IOException("S3 get failed for " + uri(key), e);
        }
    }

    @Override
    public RangedObject openRange(String key, long from, long toInclusive) throws IOException {
        String range = toInclusive < 0 ? "bytes=" + from + "-" : "bytes=" + from + "-" + toInclusive;
        try {
            ResponseInputStream<GetObjectResponse> body = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).range(range).build());
            GetObjectResponse response = body.response();
            long total;
            long start = from;
            long end;
            String contentRange = response.contentRange();
            if (contentRange != null && contentRange.startsWith("bytes ")) {
                // "bytes 0-99/1234"
                String[] parts = contentRange.substring(6).split("/");
                String[] se = parts[0].split("-");
                start = Long.parseLong(se[0]);
                end = Long.parseLong(se[1]);
                total = Long.parseLong(parts[1]);
            } else {
                // server ignored the range (e.g. whole object requested from 0)
                total = response.contentLength() == null ? 0 : response.contentLength();
                end = total - 1;
                start = 0;
            }
            return new RangedObject(body, start, end, total, response.eTag(), response.contentType());
        } catch (NoSuchKeyException e) {
            throw new java.nio.file.NoSuchFileException(uri(key));
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                throw new RangeNotSatisfiableException(uri(key), range);
            }
            throw new IOException("S3 ranged get failed for " + uri(key), e);
        } catch (SdkException e) {
            throw new IOException("S3 ranged get failed for " + uri(key), e);
        }
    }

    @Override
    public void put(String key, Path file, String contentType) throws IOException {
        try {
            PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key);
            if (contentType != null) {
                request.contentType(contentType);
            }
            client.putObject(request.build(), RequestBody.fromFile(file));
        } catch (SdkException e) {
            throw new IOException("S3 put failed for " + uri(key), e);
        }
    }

    @Override
    public void put(String key, InputStream body, long length, String contentType) throws IOException {
        try {
            PutObjectRequest.Builder request = PutObjectRequest.builder().bucket(bucket).key(key);
            if (contentType != null) {
                request.contentType(contentType);
            }
            client.putObject(request.build(), RequestBody.fromInputStream(body, length));
        } catch (SdkException e) {
            throw new IOException("S3 put failed for " + uri(key), e);
        }
    }

    @Override
    public boolean delete(String key) throws IOException {
        try {
            if (stat(key).isEmpty()) {
                return false;
            }
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (SdkException e) {
            throw new IOException("S3 delete failed for " + uri(key), e);
        }
    }

    @Override
    public void copyToLocal(String key, Path target) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.deleteIfExists(tmp);
            client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
                    ResponseTransformer.toFile(tmp));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (NoSuchKeyException e) {
            throw new java.nio.file.NoSuchFileException(uri(key));
        } catch (SdkException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("S3 download failed for " + uri(key), e);
        }
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build();
        return presigner.presignGetObject(request).url().toString();
    }

    public void close() {
        presigner.close();
        client.close();
    }

    /** The requested byte range starts past the end of the object. */
    public static class RangeNotSatisfiableException extends IOException {
        public RangeNotSatisfiableException(String uri, String range) {
            super("Range " + range + " not satisfiable for " + uri);
        }
    }
}
