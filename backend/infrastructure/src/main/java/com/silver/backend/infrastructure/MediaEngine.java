package com.silver.backend.infrastructure;

import com.silver.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class MediaEngine {
    public record Info(String mime, String type, Integer width, Integer height, Long durationMs) {}
    public record Output(String playback, String thumbnail) {}

    @Autowired
    private FileStorage storage;
    @Autowired
    private JsonCodec json;
    @Value("${htw.ffmpeg:ffmpeg}")
    private String ffmpeg;
    @Value("${htw.ffprobe:ffprobe}")
    private String ffprobe;
    @Value("${htw.media.probe-timeout-seconds:20}")
    private int probeTimeout = 20;
    @Value("${htw.media.process-timeout-seconds:480}")
    private int processTimeout = 480;
    @Value("${htw.media.thumbnail-timeout-seconds:60}")
    private int thumbnailTimeout = 60;
    @Value("${htw.media.max-log-bytes:2000000}")
    private long maxLogBytes = 2_000_000;
    @Value("${htw.media.max-output-bytes:536870912}")
    private long maxOutputBytes = 536_870_912;
    @Value("${htw.media.threads:2}")
    private int threads = 2;

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
            } else if (Arrays.equals(
                    Arrays.copyOf(head, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})) {
                mime = "image/png";
            } else if (new String(head, 0, 6, StandardCharsets.US_ASCII).matches("GIF8[79]a")) {
                mime = "image/gif";
            }
            if (mime != null) {
                BufferedImage image = readImage(file);
                if (image == null) throw new IOException("Unsupported image decoder");
                return new Info(mime, "IMAGE", image.getWidth(), image.getHeight(), null);
            }
        }
        return probe(file, head);
    }

    public Output process(String id, String key, Info info) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,160}")) {
            throw new IllegalArgumentException("Invalid media output id");
        }
        Objects.requireNonNull(info, "info");
        Path input = storage.path(key);
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Media input is not a regular file");
        }
        List<String> created = new ArrayList<>();
        try {
            if ("IMAGE".equals(info.type())) {
                String thumb = "thumbnails/" + id + ".jpg";
                reserve(thumb, created);
                image(id, input, info, thumb, created);
                checkOutputs(null, created);
                return new Output(key, thumb);
            }
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "不支持的媒体分类");
        } catch (IOException | RuntimeException | Error failure) {
            discard(created, failure);
            throw failure;
        }
    }

    private BufferedImage readImage(Path file) throws IOException {
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("Cannot open image input");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                checkPixels(reader.getWidth(0), reader.getHeight(0));
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("Corrupt image");
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    private void image(String id, Path input, Info info, String thumb, List<String> created)
            throws IOException {
        BufferedImage image = readImage(input);
        String frame = null;
        if (image == null) {
            if (!"image/webp".equals(info.mime())) throw new IOException("Unsupported image decoder");
            // 先输出透明 PNG，再统一生成白底 JPEG。
            frame = "temporary/" + id + "-frame.png";
            reserve(frame, created);
            run(List.of(
                    ffmpeg, "-nostdin", "-v", "error", "-y", "-filter_threads", "1",
                    "-protocol_whitelist", "file,pipe", "-threads", Integer.toString(threads),
                    "-i", input.toString(), "-map", "0:v:0", "-frames:v", "1",
                    "-vf", "scale=w='min(640,iw)':h='min(640,ih)':force_original_aspect_ratio=decrease",
                    "-threads", Integer.toString(threads), storage.path(frame).toString()),
                    thumbnailTimeout, created);
            image = readImage(storage.path(frame));
            if (image == null) throw new IOException("Cannot decode generated image frame");
        }
        double ratio = Math.min(1, 640d / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) (image.getWidth() * ratio));
        int height = Math.max(1, (int) (image.getHeight() * ratio));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(output, "jpg", storage.path(thumb).toFile())) {
            throw new IOException("No JPEG writer");
        }
        if (frame != null) {
            storage.delete(frame);
            created.remove(frame);
        }
    }

    private Path reserve(String key, List<String> created) throws IOException {
        Path path = storage.output(key);
        Files.createFile(path);
        created.add(key);
        return path;
    }

    private void discard(List<String> created, Throwable failure) {
        for (String key : created) {
            try {
                storage.delete(key);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Info probe(Path file, byte[] head) throws IOException {
        // 先筛选实际支持的容器，再交给 FFprobe 解析。
        String container = probeContainer(file, head);

        Map<String, Object> result = json.readMap(
                run(
                        List.of(
                                ffprobe,
                                "-protocol_whitelist", "file,pipe",
                                "-v", "error",
                                "-show_streams",
                                "-show_format",
                                "-show_entries",
                                "format=format_name,duration:"
                                        + "stream=codec_type,codec_name,width,height,duration:"
                                        + "stream_disposition=attached_pic",
                                "-of", "json",
                                file.toString()),
                        probeTimeout));

        List<Map<String, Object>> streams =
                (List<Map<String, Object>>) result.getOrDefault("streams", List.of());
        Map<String, Object> format =
                (Map<String, Object>) result.getOrDefault("format", Map.of());
        List<String> formats = Arrays.asList(
                Objects.toString(format.get("format_name"), "").split(","));

        String expected = switch (container) {
            case "mp4" -> "mov";
            case "webm" -> "matroska";
            case "webp" -> "webp_pipe";
            default -> container;
        };
        if (streams.isEmpty() || !formats.contains(expected)) {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "不支持的真实媒体格式");
        }

        Map<String, Object> video = streams.stream()
                .filter(s -> "video".equals(s.get("codec_type")))
                .filter(s -> {
                    Map<String, Object> disposition =
                            (Map<String, Object>) s.getOrDefault("disposition", Map.of());
                    Object attached = disposition.get("attached_pic");
                    return !(attached instanceof Number n && n.intValue() == 1);
                })
                .findFirst()
                .orElse(null);

        Map<String, Object> audio = streams.stream()
                .filter(s -> "audio".equals(s.get("codec_type")))
                .findFirst()
                .orElse(null);

        String type;
        String mime;
        Map<String, Object> primary;
        if ("webp".equals(container)
                && video != null
                && "webp".equals(video.get("codec_name"))) {
            type = "IMAGE";
            mime = "image/webp";
            primary = video;
        } else if (video != null
                && ("mp4".equals(container) || "webm".equals(container))) {
            type = "VIDEO";
            mime = "mp4".equals(container) ? "video/mp4" : "video/webm";
            primary = video;
        } else if (video == null && audio != null) {
            type = "AUDIO";
            mime = switch (container) {
                case "mp3" -> "audio/mpeg";
                case "wav" -> "audio/wav";
                case "flac" -> "audio/flac";
                case "ogg" -> "audio/ogg";
                case "mp4" -> "audio/mp4";
                default -> throw new ApiException(
                        415, "UNSUPPORTED_MEDIA_TYPE", "不支持的音频容器");
            };
            primary = audio;
        } else {
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "没有可用的媒体流");
        }

        Integer width = null;
        Integer height = null;
        if (!"AUDIO".equals(type)) {
            Object rawWidth = primary.get("width");
            Object rawHeight = primary.get("height");
            if (!(rawWidth instanceof Number w) || !(rawHeight instanceof Number h)
                    || w.longValue() <= 0 || w.longValue() > Integer.MAX_VALUE
                    || h.longValue() <= 0 || h.longValue() > Integer.MAX_VALUE) {
                throw new ApiException(422, "VALIDATION_FAILED", "无法读取有效的画面尺寸");
            }
            width = w.intValue();
            height = h.intValue();
            checkPixels(width, height);
        }

        Long durationMs = null;
        if (!"IMAGE".equals(type)) {
            String duration = Objects.toString(format.get("duration"), "");
            if (duration.isBlank() || "N/A".equals(duration)) {
                duration = Objects.toString(primary.get("duration"), "");
            }
            try {
                double seconds = Double.parseDouble(duration);
                double millis = seconds * 1000;
                if (!Double.isFinite(millis) || seconds < 0 || millis >= Long.MAX_VALUE) {
                    throw new NumberFormatException("Invalid media duration");
                }
                durationMs = Math.round(millis);
            } catch (NumberFormatException invalid) {
                throw new ApiException(422, "VALIDATION_FAILED", "无法读取有效的媒体时长");
            }
        }

        return new Info(mime, type, width, height, durationMs);
    }

    private String probeContainer(Path file, byte[] head) throws IOException {
        String prefix = new String(head, StandardCharsets.ISO_8859_1);

        if (head.length >= 12 && prefix.startsWith("RIFF")) {
            String kind = prefix.substring(8, 12);
            if ("WEBP".equals(kind)) return "webp";
            if ("WAVE".equals(kind)) return "wav";
        }
        if (prefix.startsWith("fLaC")) return "flac";
        if (prefix.startsWith("OggS")) return "ogg";
        if (prefix.startsWith("ID3")
                || head.length >= 2
                && (head[0] & 255) == 255
                && (head[1] & 224) == 224) {
            return "mp3";
        }

        if (head.length >= 12 && "ftyp".equals(prefix.substring(4, 8))) {
            String brand = prefix.substring(8, 12);
            if (brand.matches("iso[2-9]") || Set.of(
                    "isom", "mp41", "mp42", "avc1", "M4A ", "M4B ",
                    "M4V ", "MSNV", "dash", "cmfc", "cmfs").contains(brand)) {
                return "mp4";
            }
        }

        if (head.length >= 4
                && (head[0] & 255) == 0x1a
                && (head[1] & 255) == 0x45
                && (head[2] & 255) == 0xdf
                && (head[3] & 255) == 0xa3
                && webmHeader(file)) {
            return "webm";
        }

        throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "不支持的真实媒体格式");
    }

    private boolean webmHeader(Path file) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            // 只读取有限的头部，本项目不接受超过此范围的 EBML 头。
            bytes = input.readNBytes(4096);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.remaining() < 5 || buffer.getInt() != 0x1a45dfa3) return false;

        long headerSize = ebmlNumber(buffer, true);
        if (headerSize < 0 || headerSize > buffer.remaining()) return false;
        buffer.limit(buffer.position() + (int) headerSize);

        boolean found = false;
        while (buffer.hasRemaining()) {
            long id = ebmlNumber(buffer, false);
            long size = ebmlNumber(buffer, true);
            if (id < 0 || size < 0 || size > buffer.remaining()) return false;

            if (id == 0x4282) {
                if (found || size != 4) return false;
                byte[] value = new byte[4];
                buffer.get(value);
                if (!"webm".equals(new String(value, StandardCharsets.US_ASCII))) return false;
                found = true;
            } else {
                buffer.position(buffer.position() + (int) size);
            }
        }
        return found;
    }

    private long ebmlNumber(ByteBuffer buffer, boolean size) {
        if (!buffer.hasRemaining()) return -1;
        int first = buffer.get() & 255;
        if (first == 0) return -1;

        int marker = 128;
        int length = 1;
        while ((first & marker) == 0) {
            marker >>= 1;
            length++;
        }
        if ((!size && length > 4) || buffer.remaining() < length - 1) return -1;

        long value = size ? first & (marker - 1) : first;
        for (int i = 1; i < length; i++) {
            value = (value << 8) | (buffer.get() & 255);
        }
        // 不接受表示“未知长度”的大小字段。
        if (size && value == (1L << (7 * length)) - 1) return -1;
        return value;
    }

    private String run(List<String> command, int timeout) throws IOException {
        return run(command, timeout, List.of());
    }

    private String run(List<String> command, int timeout, List<String> outputs) throws IOException {
        if (timeout <= 0 || maxLogBytes <= 0 || maxOutputBytes <= 0 || threads <= 0) {
            throw new IllegalStateException("Media processing limits must be positive");
        }
        Path log = Files.createTempFile(
                storage.output("temporary/media.log").getParent(), "media-", ".log");
        Process process = null;
        Throwable failure = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            process.getOutputStream().close();
            long started = System.nanoTime();
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
                checkOutputs(log, outputs);
                if (System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(timeout)) {
                    throw new IOException("Media processor timed out");
                }
            }
            checkOutputs(log, outputs);
            if (process.exitValue() != 0) throw new IOException("Media processor rejected input");
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IOException interrupted = new IOException("Media processing interrupted", e);
            failure = interrupted;
            throw interrupted;
        } catch (IOException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            IOException cleanup = null;
            try {
                stop(process);
            } catch (IOException e) {
                cleanup = e;
            }
            try {
                Files.deleteIfExists(log);
            } catch (IOException e) {
                if (cleanup == null) cleanup = e;
                else cleanup.addSuppressed(e);
            }
            if (cleanup != null) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    private void checkOutputs(Path log, List<String> outputs) throws IOException {
        if (log != null && Files.size(log) > maxLogBytes) throw new IOException("Media log too large");
        long total = 0;
        for (String key : outputs) {
            Path path = storage.path(key);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid media output");
            }
            long size = Files.size(path);
            if (size > maxOutputBytes - total) throw new IOException("Media output too large");
            total += size;
        }
    }

    private void stop(Process process) throws IOException {
        if (process == null || !process.isAlive()) return;
        // 暂时清除中断标记，等待子进程退出，最后恢复标记。
        boolean interrupted = Thread.interrupted();
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("Media processor did not terminate");
            }
        } catch (InterruptedException e) {
            interrupted = true;
            throw new IOException("Interrupted while terminating media processor", e);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
