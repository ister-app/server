package app.ister.disk.events.mediafilefound;

import app.ister.disk.events.mediafilefound.MediaFileFoundDetectCrop.CropRect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaFileFoundDetectCropTest {

    @Test
    void parseCropLineReadsWidthHeightXY() {
        var crop = MediaFileFoundDetectCrop.parseCropLine(
                "[Parsed_cropdetect_0 @ 0x55] x1:0 x2:719 y1:74 y2:501 w:720 h:428 x:0 y:74 pts:12 t:0.5 crop=720:428:0:74");
        assertTrue(crop.isPresent());
        assertEquals(new CropRect(0, 74, 720, 428), crop.get());
    }

    @Test
    void parseCropLineRejectsGarbage() {
        assertTrue(MediaFileFoundDetectCrop.parseCropLine("frame=  60 fps=0.0").isEmpty());
    }

    @Test
    void unionKeepsEverythingAnySampleConsideredPicture() {
        // A dark sample over-crops (smaller picture rect); the union discards that.
        var result = MediaFileFoundDetectCrop.union(List.of(
                new CropRect(0, 74, 720, 428),
                new CropRect(100, 120, 400, 300),
                new CropRect(0, 72, 720, 432)));
        assertEquals(new CropRect(0, 72, 720, 432), result);
    }

    @Test
    void finalizeKeepsMeaningfulLetterbox() {
        // 720x576 frame with 74px bars top/bottom.
        var result = MediaFileFoundDetectCrop.finalizeCrop(new CropRect(0, 74, 720, 428), 720, 576);
        assertEquals(new CropRect(0, 74, 720, 428), result);
    }

    @Test
    void finalizeReturnsFullFrameForTinyBars() {
        // 8px bars on 1080 = under the 3% threshold.
        var result = MediaFileFoundDetectCrop.finalizeCrop(new CropRect(0, 8, 1920, 1064), 1920, 1080);
        assertEquals(new CropRect(0, 0, 1920, 1080), result);
    }

    @Test
    void finalizeReturnsFullFrameForImplausiblyLargeBars() {
        // Half the frame "cropped" = dark-scene misdetection.
        var result = MediaFileFoundDetectCrop.finalizeCrop(new CropRect(0, 270, 1920, 540), 1920, 1080);
        assertEquals(new CropRect(0, 0, 1920, 1080), result);
    }

    @Test
    void finalizeRoundsToEvenAndClamps() {
        var result = MediaFileFoundDetectCrop.finalizeCrop(new CropRect(1, 73, 719, 429), 720, 576);
        assertEquals(0, result.x() % 2);
        assertEquals(0, result.y() % 2);
        assertTrue(result.x() + result.w() <= 720);
        assertTrue(result.y() + result.h() <= 576);
        // Still a meaningful vertical crop.
        assertTrue(result.h() < 576);
    }
}
