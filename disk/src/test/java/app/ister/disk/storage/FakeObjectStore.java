package app.ister.disk.storage;

import app.ister.core.storage.ObjectStat;
import app.ister.core.storage.ObjectStore;
import app.ister.core.storage.RangedObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** In-memory {@link ObjectStore} for unit tests: a sorted key → bytes map with S3's listing semantics. */
public class FakeObjectStore implements ObjectStore {

    private final String bucket;
    private final Map<String, byte[]> objects = new TreeMap<>();
    private final Map<String, Instant> modified = new TreeMap<>();
    /** uploadId → key, and uploadId → (part number → bytes). */
    private final Map<String, String> multipartKeys = new TreeMap<>();
    private final Map<String, Map<Integer, byte[]>> multipartParts = new TreeMap<>();

    public FakeObjectStore(String bucket) {
        this.bucket = bucket;
    }

    public FakeObjectStore put(String key, String content) {
        objects.put(key, content.getBytes());
        modified.put(key, Instant.parse("2024-01-01T00:00:00Z"));
        return this;
    }

    public FakeObjectStore put(String key, byte[] content) {
        objects.put(key, content);
        modified.put(key, Instant.parse("2024-01-01T00:00:00Z"));
        return this;
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public Stream<ObjectStat> list(String prefix) {
        return objects.keySet().stream().filter(k -> k.startsWith(prefix)).map(this::statOf);
    }

    @Override
    public Listing listShallow(String prefix) {
        List<ObjectStat> direct = new ArrayList<>();
        List<String> children = new ArrayList<>();
        for (String key : objects.keySet()) {
            if (!key.startsWith(prefix) || key.equals(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                direct.add(statOf(key));
            } else {
                String child = prefix + rest.substring(0, slash);
                if (!children.contains(child)) {
                    children.add(child);
                }
            }
        }
        return new Listing(direct, children);
    }

    private ObjectStat statOf(String key) {
        return new ObjectStat(key, objects.get(key).length, modified.get(key), "\"etag-" + key.hashCode() + "\"", null);
    }

    @Override
    public Optional<ObjectStat> stat(String key) {
        return objects.containsKey(key) ? Optional.of(statOf(key)) : Optional.empty();
    }

    @Override
    public InputStream open(String key) throws IOException {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new NoSuchFileException(uri(key));
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public RangedObject openRange(String key, long from, long toInclusive) throws IOException {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new NoSuchFileException(uri(key));
        }
        long end = toInclusive < 0 || toInclusive >= bytes.length ? bytes.length - 1 : toInclusive;
        byte[] slice = Arrays.copyOfRange(bytes, (int) from, (int) end + 1);
        return new RangedObject(new ByteArrayInputStream(slice), from, end, bytes.length, statOf(key).etag(), null);
    }

    @Override
    public void put(String key, Path file, String contentType) throws IOException {
        put(key, Files.readAllBytes(file));
    }

    @Override
    public void put(String key, InputStream body, long length, String contentType) throws IOException {
        put(key, body.readAllBytes());
    }

    @Override
    public String createMultipartUpload(String key, String contentType) {
        String uploadId = UUID.randomUUID().toString();
        multipartKeys.put(uploadId, key);
        multipartParts.put(uploadId, new TreeMap<>());
        return uploadId;
    }

    @Override
    public String uploadPart(String key, String uploadId, int partNumber, InputStream body, long length)
            throws IOException {
        Map<Integer, byte[]> parts = multipartParts.get(uploadId);
        if (parts == null) {
            throw new IOException("No such upload: " + uploadId);
        }
        byte[] bytes = body.readNBytes((int) length);
        parts.put(partNumber, bytes);
        return "\"part-" + partNumber + "-" + Arrays.hashCode(bytes) + "\"";
    }

    @Override
    public void completeMultipartUpload(String key, String uploadId, List<UploadedPart> parts) throws IOException {
        Map<Integer, byte[]> stored = multipartParts.remove(uploadId);
        multipartKeys.remove(uploadId);
        if (stored == null) {
            throw new IOException("No such upload: " + uploadId);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (UploadedPart part : parts.stream().sorted(java.util.Comparator.comparingInt(UploadedPart::partNumber)).toList()) {
            byte[] bytes = stored.get(part.partNumber());
            if (bytes == null) {
                throw new IOException("Missing part " + part.partNumber());
            }
            out.writeBytes(bytes);
        }
        put(key, out.toByteArray());
    }

    @Override
    public void abortMultipartUpload(String key, String uploadId) {
        multipartParts.remove(uploadId);
        multipartKeys.remove(uploadId);
    }

    @Override
    public boolean delete(String key) {
        modified.remove(key);
        return objects.remove(key) != null;
    }

    @Override
    public void copyToLocal(String key, Path target) throws IOException {
        byte[] bytes = objects.get(key);
        if (bytes == null) {
            throw new NoSuchFileException(uri(key));
        }
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        return "https://fake/" + bucket + "/" + key + "?X-Amz-Signature=fake";
    }
}
