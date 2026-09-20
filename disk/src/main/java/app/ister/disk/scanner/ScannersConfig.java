package app.ister.disk.scanner;

import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.ComicScanner;
import app.ister.disk.scanner.scanners.EpubScanner;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.SubtitleScanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ScannersConfig {

    @Bean
    @SuppressWarnings("java:S107") // one parameter per scanner
    public Scanners scanners(MediaFileScanner mediaFileScanner, ImageScanner imageScanner, NfoScanner nfoScanner,
                             SubtitleScanner subtitleScanner, AudioScanner audioScanner, EpubScanner epubScanner,
                             ComicScanner comicScanner) {
        return new Scanners(mediaFileScanner, imageScanner, nfoScanner, subtitleScanner, audioScanner, epubScanner,
                comicScanner);
    }
}
