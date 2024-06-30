package app.ister.server.events.TMDBMetadata;

import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

@Component
public class ImageDownload {
    public void download(String imageUrl, String toPath) throws IOException {
        URL url = new URL(imageUrl);
        ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
        try (FileOutputStream fileOutputStream = new FileOutputStream(toPath)) {
            FileChannel fileChannel = fileOutputStream.getChannel();
            fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        }
    }
}
