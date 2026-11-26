package com.silver.backend;

import com.silver.backend.common.ApiException;
import com.silver.backend.infrastructure.FileStorage;
import com.silver.backend.infrastructure.JsonCodec;
import com.silver.backend.infrastructure.MediaEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class MediaEngineTest {
    @TempDir Path directory;
    private FileStorage storage;
    private MediaEngine engine;
    private JsonCodec json;

    @BeforeEach
    void setUp() throws IOException {
        storage = new FileStorage(directory.resolve("media files").toString());
        json = spy(new JsonCodec());
        ReflectionTestUtils.setField(json, "mapper", JsonMapper.builder().build());
        engine = new MediaEngine();
        ReflectionTestUtils.setField(engine, "storage", storage);
        ReflectionTestUtils.setField(engine, "json", json);
        ReflectionTestUtils.setField(engine, "ffmpeg", tool("ffmpeg"));
        ReflectionTestUtils.setField(engine, "ffprobe", tool("ffprobe"));
    }

    @ParameterizedTest
    @CsvSource({"png,image/png", "jpg,image/jpeg", "gif,image/gif"})
    void imageInfo(String format, String mime) throws Exception {
        String key = image(format, 96, 48, false);
        MediaEngine.Info info = engine.inspect(key);
        assertEquals(new MediaEngine.Info(mime, "IMAGE", 96, 48, null), info);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "plain text", "#EXTM3U\nhttps://example.invalid/video", "<svg/>"})
    void unsupported(String text) throws Exception {
        String key = "originals/fake.mp4";
        write(key, text.getBytes(StandardCharsets.UTF_8));
        ApiException error = assertThrows(ApiException.class, () -> engine.inspect(key));
        assertEquals(415, error.status);
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.code);
        assertNoLogs();
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg", "gif"})
    void corruptImage(String format) throws Exception {
        String key = image(format, 40, 20, false);
        byte[] original = Files.readAllBytes(storage.path(key));
        write(key, java.util.Arrays.copyOf(original, 8));
        assertThrows(IOException.class, () -> engine.inspect(key));
    }

    @Test
    void pixelLimit() throws Exception {
        String key = image("png", 1, 1, false);
        byte[] bytes = Files.readAllBytes(storage.path(key));
        ByteBuffer.wrap(bytes).putInt(16, 50_000).putInt(20, 50_000);
        CRC32 crc = new CRC32();
        crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        write(key, bytes);
        ApiException error = assertThrows(ApiException.class, () -> engine.inspect(key));
        assertEquals(422, error.status);
        assertEquals("VALIDATION_FAILED", error.code);
    }

    @ParameterizedTest
    @CsvSource({"1280,720,640,360", "320,180,320,180", "1,1000,1,640", "1000,1,640,1"})
    void thumbnailSize(int width, int height, int expectedWidth, int expectedHeight) throws Exception {
        String key = image("png", width, height, false);
        byte[] original = Files.readAllBytes(storage.path(key));
        MediaEngine.Output output = engine.process("picture", key, engine.inspect(key));
        assertEquals(key, output.playback());
        assertEquals("thumbnails/picture.jpg", output.thumbnail());
        BufferedImage thumbnail = ImageIO.read(storage.path(output.thumbnail()).toFile());
        assertNotNull(thumbnail);
        assertEquals(expectedWidth, thumbnail.getWidth());
        assertEquals(expectedHeight, thumbnail.getHeight());
        assertArrayEquals(original, Files.readAllBytes(storage.path(key)));
        assertEquals(List.of(key, output.thumbnail()), files());
    }

    @Test
    void whiteBackground() throws Exception {
        String key = image("png", 40, 20, true);
        MediaEngine.Output output = engine.process("alpha", key, engine.inspect(key));
        BufferedImage thumbnail = ImageIO.read(storage.path(output.thumbnail()).toFile());
        assertWhite(thumbnail.getRGB(30, 10));
        Color red = new Color(thumbnail.getRGB(5, 10));
        assertTrue(red.getRed() > 230 && red.getGreen() < 30 && red.getBlue() < 30);
    }

    @Test
    void gifFirstFrame() throws Exception {
        String key = "originals/animated.gif";
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (var out = ImageIO.createImageOutputStream(storage.output(key).toFile())) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            for (Color color : List.of(Color.RED, Color.BLUE)) {
                BufferedImage frame = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < 10; y++) for (int x = 0; x < 20; x++) frame.setRGB(x, y, color.getRGB());
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        MediaEngine.Output output = engine.process("gif", key, engine.inspect(key));
        Color first = new Color(ImageIO.read(storage.path(output.thumbnail()).toFile()).getRGB(5, 5));
        assertTrue(first.getRed() > 230 && first.getBlue() < 30);
    }

    @Test
    void existingOutput() throws Exception {
        String key = image("png", 40, 20, false);
        MediaEngine.Info info = engine.inspect(key);
        MediaEngine.Output first = engine.process("same", key, info);
        byte[] before = Files.readAllBytes(storage.path(first.thumbnail()));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> engine.process("same", key, info));
        assertArrayEquals(before, Files.readAllBytes(storage.path(first.thumbnail())));
        assertEquals(List.of(key, first.thumbnail()), files());
    }

    @Test
    void imageOutputLimit() throws Exception {
        String key = image("png", 40, 20, false);
        ReflectionTestUtils.setField(engine, "maxOutputBytes", 32L);
        IOException error = assertThrows(IOException.class, () -> engine.process("limit", key, engine.inspect(key)));
        assertTrue(error.getMessage().contains("output too large"));
        assertEquals(List.of(key), files());
    }

    @Test
    void cleanupFailure() throws Exception {
        String key = "originals/corrupt";
        write(key, "broken".getBytes(StandardCharsets.UTF_8));
        FileStorage failing = spy(storage);
        doThrow(new IOException("cleanup failed")).when(failing).delete("thumbnails/fail.jpg");
        ReflectionTestUtils.setField(engine, "storage", failing);
        IOException error = assertThrows(IOException.class,
                () -> engine.process("fail", key, new MediaEngine.Info("image/png", "IMAGE", 10, 10, null)));
        assertNotEquals("cleanup failed", error.getMessage());
        assertEquals("cleanup failed", error.getSuppressed()[0].getMessage());
        assertTrue(Files.exists(storage.path(key)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "../escape", "part/id", "file.jpg"})
    void invalidOutputId(String id) throws Exception {
        String key = image("png", 10, 10, false);
        assertThrows(IllegalArgumentException.class, () -> engine.process(id, key, engine.inspect(key)));
        assertEquals(List.of(key), files());
    }

    @Test
    void unknownType() throws Exception {
        String key = image("png", 10, 10, false);
        ApiException error = assertThrows(ApiException.class,
                () -> engine.process("unknown", key, new MediaEngine.Info("x", "UNKNOWN", null, null, null)));
        assertEquals(415, error.status);
        assertEquals(List.of(key), files());
    }

    @Test
    void missingInput() {
        assertThrows(IOException.class,
                () -> engine.process("missing", "originals/missing", new MediaEngine.Info("x", "AUDIO", null, null, 100L)));
    }

    @Test
    void missingProbe() throws Exception {
        String key = fixture("audio.mp3");
        ReflectionTestUtils.setField(engine, "ffprobe", directory.resolve("not-installed.exe").toString());
        assertThrows(IOException.class, () -> engine.inspect(key));
        assertNoLogs();
        assertEquals(List.of(key), files());
    }

    @ParameterizedTest
    @ValueSource(strings = {"qt  ", "3gp5"})
    void rejectedBrand(String brand) throws Exception {
        String key = "originals/container";
        write(key, ByteBuffer.allocate(32).putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII))
                .put(brand.getBytes(StandardCharsets.US_ASCII)).array());
        ApiException error = assertThrows(ApiException.class, () -> engine.inspect(key));
        assertEquals(415, error.status);
        assertNoLogs();
    }

    @Test
    void rejectsMatroska() throws Exception {
        String key = "originals/mkv";
        write(key, new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, (byte) 0x8b,
                0x42, (byte) 0x82, (byte) 0x88, 'm', 'a', 't', 'r', 'o', 's', 'k', 'a'});
        ApiException error = assertThrows(ApiException.class, () -> engine.inspect(key));
        assertEquals(415, error.status);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 5, 8})
    void brokenWebm(int length) throws Exception {
        byte[] header = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, (byte) 0x87, 0x42, (byte) 0x82};
        String key = "originals/short";
        write(key, java.util.Arrays.copyOf(header, length));
        ApiException error = assertThrows(ApiException.class, () -> engine.inspect(key));
        assertEquals(415, error.status);
    }

    @Test
    void separateExecutions() throws Exception {
        String key = image("png", 40, 20, false);
        MediaEngine.Info info = engine.inspect(key);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> engine.process("attempt-a", key, info));
            var b = pool.submit(() -> engine.process("attempt-b", key, info));
            assertNotEquals(a.get(10, TimeUnit.SECONDS).thumbnail(), b.get(10, TimeUnit.SECONDS).thumbnail());
            assertEquals(3, files().size());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSameId() throws Exception {
        String key = image("png", 40, 20, false);
        MediaEngine.Info info = engine.inspect(key);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    engine.process("same-attempt", key, info);
                    return true;
                } catch (java.nio.file.FileAlreadyExistsException expected) {
                    return false;
                }
            }));
            start.countDown();
            int successful = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) successful++;
            assertEquals(1, successful);
            assertNotNull(ImageIO.read(storage.path("thumbnails/same-attempt.jpg").toFile()));
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void processText() throws Exception {
        assertEquals("processor output", run(stub("text"), 10, List.of()));
        assertNoLogs();
    }

    @Test
    void processFailure() throws Exception {
        IOException error = assertThrows(IOException.class, () -> run(stub("fail"), 10, List.of()));
        assertTrue(error.getMessage().contains("rejected input"));
        assertNoLogs();
    }

    @Test
    void processTimeout() throws Exception {
        IOException error = assertThrows(IOException.class, () -> run(stub("sleep"), 1, List.of()));
        assertTrue(error.getMessage().contains("timed out"));
        assertStopped();
        assertNoLogs();
    }

    @Test
    void logLimit() throws Exception {
        ReflectionTestUtils.setField(engine, "maxLogBytes", 100L);
        IOException error = assertThrows(IOException.class, () -> run(stub("log"), 10, List.of()));
        assertTrue(error.getMessage().contains("log too large"));
        assertStopped();
        assertNoLogs();
    }

    @Test
    void growingOutput() throws Exception {
        ReflectionTestUtils.setField(engine, "maxOutputBytes", 100L);
        String key = "processed/growing.bin";
        storage.output(key);
        IOException error = assertThrows(IOException.class, () -> run(stub("output"), 10, List.of(key)));
        assertTrue(error.getMessage().contains("output too large"));
        assertStopped();
        assertNoLogs();
    }

    @Test
    void processInterrupted() throws Exception {
        List<String> command = stub("sleep");
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                run(command, 10, List.of());
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(directory.resolve("child.pid")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("child.pid")));
            worker.interrupt();
            worker.join(6000);
            assertFalse(worker.isAlive());
            assertInstanceOf(IOException.class, failure.get());
            assertTrue(interrupted.get());
            assertStopped();
            assertNoLogs();
        } finally {
            worker.interrupt();
            worker.join(6000);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"threads", "maxLogBytes", "maxOutputBytes"})
    void invalidLimits(String field) throws Exception {
        ReflectionTestUtils.setField(engine, field, "threads".equals(field) ? (Object) Integer.valueOf(0) : Long.valueOf(0));
        assertThrows(IllegalStateException.class, () -> run(stub("text"), 10, List.of()));
    }

    @Nested
    @EnabledIfSystemProperty(named = "htw.media-tests", matches = "true")
    class ExternalTools {
        @BeforeAll
        static void requireTools() throws Exception {
            for (String name : List.of("ffmpeg", "ffprobe")) {
                Process process = new ProcessBuilder(tool(name), "-version").redirectErrorStream(true).start();
                String version;
                try (var input = process.getInputStream()) {
                    version = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
                }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), name + " did not exit");
                assertEquals(0, process.exitValue(), name + " is required when htw.media-tests=true");
                System.out.println(version.lines().findFirst().orElseThrow());
            }
        }

        @ParameterizedTest
        @CsvSource({"video.mp4,video/mp4,VIDEO,160,90", "silent.webm,video/webm,VIDEO,321,181",
                "audio.wav,audio/wav,AUDIO,0,0", "audio.mp3,audio/mpeg,AUDIO,0,0",
                "audio.flac,audio/flac,AUDIO,0,0", "audio.ogg,audio/ogg,AUDIO,0,0",
                "audio.m4a,audio/mp4,AUDIO,0,0", "cover.mp3,audio/mpeg,AUDIO,0,0",
                "transparent.webp,image/webp,IMAGE,32,16"})
        void mediaInfo(String name, String mime, String type, int width, int height) throws Exception {
            String key = fixture(name);
            MediaEngine.Info info = engine.inspect(key);
            assertEquals(mime, info.mime());
            assertEquals(type, info.type());
            assertEquals(width == 0 ? null : width, info.width());
            assertEquals(height == 0 ? null : height, info.height());
            if ("IMAGE".equals(type)) assertNull(info.durationMs());
            else assertTrue(info.durationMs() >= 350 && info.durationMs() <= 650);
            assertNoLogs();
        }

        @Test
        void webpThumbnail() throws Exception {
            String key = fixture("transparent.webp");
            MediaEngine.Output output = engine.process("webp", key, engine.inspect(key));
            BufferedImage thumbnail = ImageIO.read(storage.path(output.thumbnail()).toFile());
            assertEquals(32, thumbnail.getWidth());
            assertEquals(16, thumbnail.getHeight());
            assertWhite(thumbnail.getRGB(10, 5));
            assertEquals(List.of(key, output.thumbnail()), files());
        }

        @ParameterizedTest
        @ValueSource(strings = {"video.mp4", "silent.webm", "audio.wav", "audio.mp3", "audio.flac",
                "audio.ogg", "audio.m4a", "cover.mp3", "transparent.webp"})
        void withoutExtension(String name) throws Exception {
            String original = fixture(name);
            MediaEngine.Info expected = engine.inspect(original);
            String key = "originals/uploaded-asset";
            Files.move(storage.path(original), storage.output(key));
            assertEquals(expected, engine.inspect(key));
        }

        @ParameterizedTest
        @ValueSource(strings = {"video.mp4", "silent.webm"})
        void videoConversion(String name) throws Exception {
            String key = fixture(name);
            byte[] original = Files.readAllBytes(storage.path(key));
            MediaEngine.Output output = engine.process("video", key, engine.inspect(key));
            assertEquals("processed/video.mp4", output.playback());
            assertEquals("thumbnails/video.jpg", output.thumbnail());
            MediaEngine.Info info = engine.inspect(output.playback());
            assertEquals("video/mp4", info.mime());
            assertEquals(0, info.width() % 2);
            assertEquals(0, info.height() % 2);
            assertTrue(info.durationMs() >= 350 && info.durationMs() <= 650);
            BufferedImage thumbnail = ImageIO.read(storage.path(output.thumbnail()).toFile());
            assertNotNull(thumbnail);
            assertTrue(thumbnail.getWidth() <= 640 && thumbnail.getHeight() <= 640);
            assertArrayEquals(original, Files.readAllBytes(storage.path(key)));
            Map<String, Object> metadata = outputMetadata(output.playback());
            assertTrue(streams(metadata).stream().anyMatch(s -> "h264".equals(s.get("codec_name"))));
            if ("silent.webm".equals(name)) {
                assertFalse(streams(metadata).stream().anyMatch(s -> "audio".equals(s.get("codec_type"))));
            } else {
                assertTrue(streams(metadata).stream().anyMatch(s -> "aac".equals(s.get("codec_name"))));
            }
            byte[] mp4 = Files.readAllBytes(storage.path(output.playback()));
            String bytes = new String(mp4, StandardCharsets.ISO_8859_1);
            assertTrue(bytes.indexOf("moov") < bytes.indexOf("mdat"));
            assertEquals(List.of(key, output.playback(), output.thumbnail()), files());
        }

        @ParameterizedTest
        @ValueSource(strings = {"audio.wav", "audio.flac", "audio.ogg", "audio.m4a", "cover.mp3"})
        void audioConversion(String name) throws Exception {
            String key = fixture(name);
            byte[] original = Files.readAllBytes(storage.path(key));
            MediaEngine.Output output = engine.process("audio", key, engine.inspect(key));
            assertEquals("processed/audio.mp3", output.playback());
            assertNull(output.thumbnail());
            MediaEngine.Info info = engine.inspect(output.playback());
            assertEquals("audio/mpeg", info.mime());
            assertEquals("AUDIO", info.type());
            assertTrue(info.durationMs() >= 350 && info.durationMs() <= 700);
            Map<String, Object> metadata = outputMetadata(output.playback());
            assertTrue(streams(metadata).stream().anyMatch(s -> "mp3".equals(s.get("codec_name"))));
            assertFalse(streams(metadata).stream().anyMatch(s -> "video".equals(s.get("codec_type"))));
            assertArrayEquals(original, Files.readAllBytes(storage.path(key)));
            assertEquals(List.of(key, output.playback()), files());
        }

        @ParameterizedTest
        @ValueSource(strings = {"video.mp4", "audio.wav", "transparent.webp"})
        void conversionFailure(String name) throws Exception {
            String key = fixture(name);
            MediaEngine.Info info = engine.inspect(key);
            ReflectionTestUtils.setField(engine, "ffmpeg", directory.resolve("missing.exe").toString());
            assertThrows(IOException.class, () -> engine.process("failed", key, info));
            assertEquals(List.of(key), files());
        }

        @ParameterizedTest
        @ValueSource(strings = {"video.mp4", "audio.wav", "transparent.webp"})
        void conversionLimit(String name) throws Exception {
            String key = fixture(name);
            MediaEngine.Info info = engine.inspect(key);
            ReflectionTestUtils.setField(engine, "maxOutputBytes", 8L);
            IOException error = assertThrows(IOException.class, () -> engine.process("limited", key, info));
            assertTrue(error.getMessage().contains("output too large"));
            assertEquals(List.of(key), files());
        }

        @Test
        void thumbnailFailure() throws Exception {
            String key = fixture("video.mp4");
            MediaEngine.Info info = engine.inspect(key);
            // 第一条真实 FFmpeg 成功后，利用存储读取点阻断下一次缩略图启动。
            FileStorage watched = spy(storage);
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(invocation -> {
                Path path = (Path) invocation.callRealMethod();
                if (reads.incrementAndGet() >= 3 && Files.size(path) > 0) {
                    ReflectionTestUtils.setField(engine, "ffmpeg", directory.resolve("missing.exe").toString());
                }
                return path;
            }).when(watched).path("processed/fail-thumb.mp4");
            ReflectionTestUtils.setField(engine, "storage", watched);
            assertThrows(IOException.class, () -> engine.process("fail-thumb", key, info));
            assertEquals(List.of(key), files());
        }

        @ParameterizedTest
        @ValueSource(strings = {"NaN", "Infinity", "-1", "not-a-number", "1e30"})
        void invalidDuration(String duration) throws Exception {
            String key = fixture("audio.wav");
            doAnswer(invocation -> {
                Map<String, Object> result = (Map<String, Object>) invocation.callRealMethod();
                object(result.get("format")).put("duration", duration);
                for (Map<String, Object> stream : streams(result)) stream.put("duration", duration);
                return result;
            }).when(json).readMap(anyString());
            ApiException error = assertThrows(ApiException.class, () -> engine.inspect(key));
            assertEquals(422, error.status);
        }

        private Map<String, Object> outputMetadata(String key) throws Exception {
            return json.readMap(run(List.of(tool("ffprobe"), "-v", "error", "-show_streams", "-of", "json",
                    storage.path(key).toString()), 10, List.of()));
        }
    }

    private static String tool(String name) {
        return System.getProperty("htw.test." + name, System.getenv().getOrDefault(name.toUpperCase(), name));
    }

    private String image(String format, int width, int height, boolean alpha) throws IOException {
        String key = "originals/disguised-video.mp4";
        BufferedImage image = new BufferedImage(width, height,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            image.setRGB(x, y, alpha && x >= width / 2 ? 0 : Color.RED.getRGB());
        }
        assertTrue(ImageIO.write(image, format, storage.output(key).toFile()));
        return key;
    }

    private String fixture(String name) throws IOException {
        String key = "originals/" + name;
        try (var input = getClass().getResourceAsStream("/media/" + name)) {
            assertNotNull(input, name);
            Files.copy(input, storage.output(key));
        }
        return key;
    }

    private void write(String key, byte[] bytes) throws IOException {
        Files.write(storage.output(key), bytes);
    }

    private List<String> files() throws IOException {
        Path root = directory.resolve("media files");
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
    }

    private void assertNoLogs() throws IOException {
        Path temp = directory.resolve("media files/temporary");
        if (!Files.exists(temp)) return;
        try (var walk = Files.list(temp)) {
            assertEquals(0, walk.count());
        }
    }

    private void assertWhite(int rgb) {
        Color color = new Color(rgb);
        assertTrue(color.getRed() > 245 && color.getGreen() > 245 && color.getBlue() > 245);
    }

    private List<String> stub(String mode) throws IOException {
        String output = "processed/growing.bin";
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ProcessStub.class.getName(), mode, directory.resolve("child.pid").toString(),
                storage.output(output).toString());
    }

    private String run(List<String> command, int timeout, List<String> outputs) throws Exception {
        var method = MediaEngine.class.getDeclaredMethod("run", List.class, int.class, List.class);
        method.setAccessible(true);
        try {
            return (String) method.invoke(engine, command, timeout, outputs);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            throw (Error) error.getCause();
        }
    }

    private void assertStopped() throws IOException {
        long pid = Long.parseLong(Files.readString(directory.resolve("child.pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> streams(Map<String, Object> metadata) {
        return (List<Map<String, Object>>) metadata.get("streams");
    }

    public static class ProcessStub {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            switch (args[0]) {
                case "text" -> System.out.print("processor output");
                case "fail" -> System.exit(9);
                case "sleep" -> Thread.sleep(30_000);
                case "log" -> {
                    System.out.write(new byte[16_384]);
                    System.out.flush();
                    Thread.sleep(30_000);
                }
                case "output" -> {
                    Files.write(Path.of(args[2]), new byte[4096]);
                    Thread.sleep(30_000);
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
