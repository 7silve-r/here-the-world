package com.silver.backend.infrastructure;

import com.silver.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Set;

@Component
public class FileStorage {
    @Value("${htw.storage-root}")
    private String directory;
    private final Path root;

    public FileStorage() throws IOException {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Storage root must not be a symbolic link");
        }
    }

    public Path path(String key) throws IOException {
        if (key == null || !key.matches("[a-zA-Z0-9_./-]+")) {
            throw new IOException("Invalid storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IOException("Storage escape");
        }
        Path cursor = root;
        for (Path part : root.relativize(path)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("Symbolic links are forbidden");
            }
        }
        return path;
    }

    public long write(String key,
                      InputStream input,
                      long limit) throws IOException {
        Path target = this.path(key);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "upload-", ".part");
        try{
            long count = 0L;
            byte[] buffer = new byte[64 * 1024];
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.WRITE)) {
                int n;
                while ((n = input.read(buffer)) != -1) {
                    count += n;
                    if (count > limit) {
                        throw new ApiException(413, "FILE_TOO_LARGE", "文件过大");
                    }
                    output.write(buffer,0, n);
                }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return count;
        }
        finally{
            Files.deleteIfExists(temp);
        }
    }

    public Path output(String key) throws IOException {
        Path path = this.path(key);
        Files.createDirectories(path.getParent());
        return path;
    }

    public long size(String key) throws IOException {
        return Files.size(this.path(key));
    }

    public void delete(String key) throws IOException {
        if (key != null) {
            Files.deleteIfExists(this.path(key));
        }
    }

    public InputStream read(String key) throws IOException {
        return Files.newInputStream(path(key),
                                    StandardOpenOption.READ,
                                    LinkOption.NOFOLLOW_LINKS);
    }
}
