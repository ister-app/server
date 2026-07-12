package app.ister.disk.scanner;

import app.ister.disk.scanner.scanners.AudioScanner;
import app.ister.disk.scanner.scanners.EpubScanner;
import app.ister.disk.scanner.scanners.ImageScanner;
import app.ister.disk.scanner.scanners.MediaFileScanner;
import app.ister.disk.scanner.scanners.NfoScanner;
import app.ister.disk.scanner.scanners.SubtitleScanner;

record Scanners(
        MediaFileScanner mediaFile,
        ImageScanner image,
        NfoScanner nfo,
        SubtitleScanner subtitle,
        AudioScanner audio,
        EpubScanner epub) {}
