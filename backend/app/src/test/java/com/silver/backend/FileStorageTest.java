package com.silver.backend;

import com.silver.backend.common.ApiException;
import com.silver.backend.infrastructure.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {
    @TempDir Path directory;
    private FileStorage storage;

    @BeforeEach
    void createStorage() throws IOException {
        storage = new FileStorage(directory.toString());
    }

    @Test
    void writesAndReads() throws IOException {
        String key = "originals/photo.txt";
        byte[] payload = "媒体文件".getBytes(StandardCharsets.UTF_8);
        assertEquals(payload.length, storage.write(key, new ByteArrayInputStream(payload), payload.length));
        assertEquals(payload.length, storage.size(key));
        try (var input = storage.read(key)) {
            assertArrayEquals(payload, input.readAllBytes());
        }
        storage.delete(key);
        assertFalse(Files.exists(storage.path(key)));
    }

    @Test
    void removesPartialFile() throws IOException {
        String key = "originals/too-large.bin";
        byte[] payload = new byte[65_537];
        ApiException error = assertThrows(ApiException.class,
                () -> storage.write(key, new ByteArrayInputStream(payload), 65_536));
        assertEquals(413, error.status);
        assertEquals("FILE_TOO_LARGE", error.code);
        try (var entries = Files.list(directory.resolve("originals"))) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void rejectsEscape() {
        assertThrows(IOException.class, () -> storage.path("../outside.bin"));
        assertThrows(IOException.class, () -> storage.path("originals/../.."));
        assertThrows(IOException.class, () -> storage.path("."));
    }
}
