package app.ister.transcoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HardwareAccelTest {

    // ========== fromString ==========

    @Test
    void fromStringNoneIsDefault() {
        assertEquals(HardwareAccel.NONE, HardwareAccel.fromString("none"));
    }

    @Test
    void fromStringUnknownFallsBackToNone() {
        assertEquals(HardwareAccel.NONE, HardwareAccel.fromString("unknown"));
    }

    @Test
    void fromStringVaapi() {
        assertEquals(HardwareAccel.VAAPI, HardwareAccel.fromString("vaapi"));
    }

    @Test
    void fromStringNvdec() {
        assertEquals(HardwareAccel.NVDEC, HardwareAccel.fromString("nvdec"));
    }

    @Test
    void fromStringCaseInsensitive() {
        assertEquals(HardwareAccel.VAAPI, HardwareAccel.fromString("VAAPI"));
        assertEquals(HardwareAccel.NVDEC, HardwareAccel.fromString("NVDEC"));
    }

    // ========== inputArgs ==========

    @Test
    void noneHasEmptyInputArgs() {
        assertEquals(0, HardwareAccel.NONE.inputArgs("/dev/dri/renderD128").length);
    }

    @Test
    void vaapiInputArgsContainVaapiDevice() {
        // -vaapi_device must appear before -i so it is placed in inputArgs (not as a global arg),
        // which Jaffree inserts before the input path in the FFmpeg command line.
        String device = "/dev/dri/renderD128";
        String[] args = HardwareAccel.VAAPI.inputArgs(device);
        assertArrayEquals(new String[]{"-vaapi_device", device}, args);
    }

    @Test
    void vaapiInputArgsUseGivenDevice() {
        String device = "/dev/dri/renderD129";
        String[] args = HardwareAccel.VAAPI.inputArgs(device);
        assertEquals(device, args[1]);
    }

    @Test
    void nvdecInputArgsContainCuda() {
        String[] args = HardwareAccel.NVDEC.inputArgs("/dev/dri/renderD128");
        assertArrayEquals(new String[]{"-hwaccel", "cuda", "-hwaccel_output_format", "cuda"}, args);
    }

    // ========== encoder ==========

    @Test
    void noneEncoderIsNull() {
        assertNull(HardwareAccel.NONE.encoder());
    }

    @Test
    void vaapiEncoderIsH264Vaapi() {
        assertEquals("h264_vaapi", HardwareAccel.VAAPI.encoder());
    }

    @Test
    void nvdecEncoderIsH264Nvenc() {
        assertEquals("h264_nvenc", HardwareAccel.NVDEC.encoder());
    }

    // ========== scaleFilter ==========

    @Test
    void noneScaleFilterIsScale() {
        assertEquals("scale=1280:720", HardwareAccel.NONE.scaleFilter("1280:720"));
    }

    @Test
    void vaapiScaleFilterUsesSoftwareScaleWithHwupload() {
        // Software decode → scale on CPU → convert to nv12 → upload to VAAPI surface for encoding
        assertEquals("scale=1280:720,format=nv12,hwupload", HardwareAccel.VAAPI.scaleFilter("1280:720"));
    }

    @Test
    void nvdecScaleFilterIsScaleCudaWithNv12() {
        assertEquals("scale_cuda=1280:720:format=nv12", HardwareAccel.NVDEC.scaleFilter("1280:720"));
    }

    // ========== preset ==========

    @Test
    void nonePresetIsUltrafast() {
        assertEquals("ultrafast", HardwareAccel.NONE.preset());
    }

    @Test
    void vaapiPresetIsNull() {
        assertNull(HardwareAccel.VAAPI.preset());
    }

    @Test
    void nvdecPresetIsFast() {
        assertEquals("fast", HardwareAccel.NVDEC.preset());
    }
}
