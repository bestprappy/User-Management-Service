package com.navio.usermanagementservice.media;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;

class PictureValidatorTests {
    @Test void disguisedMarkupIsRejectedEvenWithAPngFilenameAndMimeType() {
        for (String text : new String[]{"<svg onload='alert(1)'/>", "<script>alert(1)</script>", "<?php system($_GET['x']); ?>"}) {
            assertThatThrownBy(() -> PictureValidator.validate(new MockMultipartFile("file", "safe.png", "image/png", text.getBytes())))
                    .isInstanceOf(PictureException.class);
        }
    }

    @Test void appendedActiveContentIsDiscardedByReencodingPixels() throws Exception {
        byte[] original = png(2, 2);
        byte[] payload = "<script>injected()</script>".getBytes(StandardCharsets.UTF_8);
        byte[] combined = Arrays.copyOf(original, original.length + payload.length);
        System.arraycopy(payload, 0, combined, original.length, payload.length);
        var picture = PictureValidator.validate(new MockMultipartFile("file", "../../payload.html", "text/html", combined));
        assertThat(picture.contentType()).isEqualTo("image/png");
        assertThat(new String(picture.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("injected", "<script>");
        assertThat(ImageIO.read(new ByteArrayInputStream(picture.bytes())).getWidth()).isEqualTo(2);
    }

    @Test void dimensionsAreCheckedBeforeDecodingTheWholeImage() throws Exception {
        assertThatThrownBy(() -> PictureValidator.validate(new MockMultipartFile("file", png(4097, 1))))
                .isInstanceOf(PictureException.class).hasMessageContaining("4096");
    }

    @Test void oversizedAndEmptyUploadsAreRejected() {
        assertThatThrownBy(() -> PictureValidator.validate(new MockMultipartFile("file", new byte[5 * 1024 * 1024 + 1])))
                .isInstanceOf(PictureException.class).hasMessageContaining("5 MiB");
        assertThatThrownBy(() -> PictureValidator.validate(new MockMultipartFile("file", new byte[0])))
                .isInstanceOf(PictureException.class);
    }

    @Test void truncatedImagesAreRejected() throws Exception {
        byte[] image = png(10, 10);
        assertThatThrownBy(() -> PictureValidator.validate(new MockMultipartFile("file", Arrays.copyOf(image, 40))))
                .isInstanceOf(PictureException.class);
    }

    @Test void validJpegPixelsRoundTripWithAServerChosenType() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpeg", output);
        var picture = PictureValidator.validate(new MockMultipartFile("file", "photo.exe", "application/octet-stream", output.toByteArray()));
        assertThat(picture.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(new ByteArrayInputStream(picture.bytes())).getHeight()).isEqualTo(2);
    }

    static byte[] png(int width, int height) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
