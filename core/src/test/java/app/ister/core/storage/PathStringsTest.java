package app.ister.core.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathStringsTest {

    @Test
    void fileNameAndParentWorkForLocalPathsAndS3Uris() {
        assertThat(PathStrings.fileName("/disk/Show (2024)/s01e01.mkv")).isEqualTo("s01e01.mkv");
        assertThat(PathStrings.fileName("s3://bucket/media/Show (2024)/s01e01.mkv")).isEqualTo("s01e01.mkv");
        assertThat(PathStrings.fileName("file.mkv")).isEqualTo("file.mkv");
        assertThat(PathStrings.parent("/disk/Show (2024)/s01e01.mkv")).isEqualTo("/disk/Show (2024)");
        assertThat(PathStrings.parent("s3://bucket/media/Show (2024)/s01e01.mkv")).isEqualTo("s3://bucket/media/Show (2024)");
        assertThat(PathStrings.parent("/file")).isEqualTo("/");
        assertThat(PathStrings.parent("s3://bucket/file")).isEqualTo("s3://bucket");
        assertThat(PathStrings.parent("file")).isNull();
    }

    @Test
    void joinNeverDoublesTheSeparator() {
        assertThat(PathStrings.join("/cache/", "a.jpg")).isEqualTo("/cache/a.jpg");
        assertThat(PathStrings.join("/cache", "a.jpg")).isEqualTo("/cache/a.jpg");
        assertThat(PathStrings.join("/cache/", "/a.jpg")).isEqualTo("/cache/a.jpg");
        assertThat(PathStrings.join("s3://b/p", "a.jpg")).isEqualTo("s3://b/p/a.jpg");
    }

    @Test
    void isUnderMatchesTheRootAndItsChildrenOnly() {
        assertThat(PathStrings.isUnder("/disk", "/disk/a")).isTrue();
        assertThat(PathStrings.isUnder("/disk/", "/disk/a")).isTrue();
        assertThat(PathStrings.isUnder("/disk", "/disk")).isTrue();
        assertThat(PathStrings.isUnder("/disk", "/disk2/a")).isFalse();
    }

    @Test
    void objectRefRoundTrips() {
        ObjectRef ref = ObjectRef.parse("s3://bucket/media/Show (2024)/s01e01.mkv");
        assertThat(ref.bucket()).isEqualTo("bucket");
        assertThat(ref.key()).isEqualTo("media/Show (2024)/s01e01.mkv");
        assertThat(ref.fileName()).isEqualTo("s01e01.mkv");
        assertThat(ref.uri()).isEqualTo("s3://bucket/media/Show (2024)/s01e01.mkv");
        assertThat(ObjectRef.parse("s3://bucket").key()).isEmpty();
        assertThat(new ObjectRef("bucket", "").uri()).isEqualTo("s3://bucket");
        assertThat(ObjectRef.isS3Uri("/disk/a")).isFalse();
        assertThatThrownBy(() -> ObjectRef.parse("/disk/a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceUrisKeepS3AndPrefixLocalPaths() {
        assertThat(SourceUris.of("/disk/a.mkv")).isEqualTo("file:///disk/a.mkv");
        assertThat(SourceUris.of("s3://b/a.mkv")).isEqualTo("s3://b/a.mkv");
    }
}
