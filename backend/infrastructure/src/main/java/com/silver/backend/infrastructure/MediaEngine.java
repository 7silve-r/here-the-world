package com.silver.backend.infrastructure;

import com.silver.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;

@Component
public class MediaEngine {
    public record Info(String mime, String type, Integer width, Integer height, Long durationMs) {}

    @Autowired
    private FileStorage storage;

    private void checkPixels(int w, int h) {
        if (w <= 0 || h <= 0 || (long) w * h > 50_000_000L) {
            throw new ApiException(422, "VALIDATION_FAILED", "图像尺寸超出处理范围");
        }
    }

    public Info inspect(String key) throws IOException {
        Path file = storage.path(key);
        byte[] head;
        try (InputStream in = storage.read(key)) {
            head = in.readNBytes(32);
        }
        if (head.length >= 8) {
            String mime = null;
            if ((head[0] & 255) == 255 && (head[1] & 255) == 216 && (head[2] & 255) == 255) {
                mime = "image/jpeg";
            }
            else if (Arrays.equals(
                    Arrays.copyOf(head, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
                mime = "image/png";
            }
            else if (new String(head, 0, 6, StandardCharsets.US_ASCII).matches("GIF8[79]a")) {
                mime = "image/gif";
            }
            if (mime != null) {
                try (var input = ImageIO.createImageInputStream(file.toFile())) {
                    Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) {
                        throw new IOException("Unsupported image decoder");
                    }
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input);
                        int w = reader.getWidth(0);
                        int h = reader.getHeight(0);
                        checkPixels(w, h);
                        if (reader.read(0) == null) {
                            throw new IOException("Corrupt image");
                        }
                        return new Info(mime, "IMAGE", w, h, null);
                    } finally {
                        reader.dispose();
                    }
                }
            }
        }
        return null;
    }
}
