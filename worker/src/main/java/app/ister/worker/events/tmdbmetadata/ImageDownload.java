package app.ister.worker.events.tmdbmetadata;

import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;

@Component
public class ImageDownload {
    // Wikimedia image hosts (Commons Special:FilePath, upload.wikimedia.org — used for artist
    // photos) reject the default Java user agent with HTTP 403, so send a descriptive one.
    private static final String USER_AGENT = "IsterServer/1.0 (info@ister.app)";

    public void download(String imageUrl, String toPath) throws IOException {
        URLConnection connection = URI.create(imageUrl).toURL().openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try (ReadableByteChannel readableByteChannel = Channels.newChannel(connection.getInputStream());
             FileOutputStream fileOutputStream = new FileOutputStream(toPath)) {
            FileChannel fileChannel = fileOutputStream.getChannel();
            fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        }
    }
}
