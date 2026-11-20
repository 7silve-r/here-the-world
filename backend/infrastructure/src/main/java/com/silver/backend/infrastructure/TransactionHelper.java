package com.silver.backend.infrastructure;

import org.apache.ibatis.annotations.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Supplier;

@Component
public class TransactionHelper {
    @Mapper
    public interface LockMapper {
        @Insert("""
            INSERT INTO resource_locks(lock_key,touched_at) 
            VALUES (#{key},#{now})
            ON DUPLICATE KEY UPDATE touched_at=#{now}
            """)
        void acquire(@Param("key") String key, @Param("now") long now);

        @Select("""
            SELECT lock_key 
            FROM resource_locks
            WHERE touched_at<#{before}
            ORDER BY touched_at,lock_key 
            LIMIT 1000
            """)
        List<String> stale(long before);

        @Delete("""
            DELETE FROM resource_locks 
            WHERE lock_key=#{key} AND touched_at<#{before}
           """)
        void cleanup(@Param("key") String key, @Param("before") long before);
    }

    @Autowired
    private PlatformTransactionManager manager;
    @Autowired
    private LockMapper locks;
    private TransactionTemplate template;

    public TransactionHelper() {
        template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private static final class Held implements TransactionSynchronization {
        final NavigableSet<String> keys = new TreeSet<>();
    }

    public void lock(Collection<String> resources) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()){
            throw new IllegalStateException("Resource locks require a transaction");
        }
        Held held =
                TransactionSynchronizationManager
                        .getSynchronizations()
                        .stream()
                        .filter(Held.class::isInstance)
                        .map(Held.class::cast)
                        .findFirst()
                        .orElseGet(
                                () -> {
                                    var state = new Held();
                                    TransactionSynchronizationManager.registerSynchronization(state);
                                    return state;
                                });
        for (String resource : new TreeSet<>(resources)) {
            Objects.requireNonNull(resource, "resource");
            if (held.keys.contains(resource)) continue;
            if (!held.keys.isEmpty() && resource.compareTo(held.keys.last()) < 0) {
                throw new IllegalStateException(
                        "Acquire resource locks in order, before reading state: " + resource);
            }
            locks.acquire(digest(resource), System.currentTimeMillis());
            held.keys.add(resource);
        }
    }

    public <T> T write(Supplier<T> action) {
        return template.execute(status -> action.get());
    }

    public <T> T write(String resource, Supplier<T> action) {
        return this.write(List.of(resource), action);
    }

    public <T> T write(Collection<String> resources, Supplier<T> action) {
        return this.write(
                () -> {
                    lock(resources);
                    return action.get();
                }
        );
    }

    public void run(Runnable action) {
        this.write(
                () -> {
                    action.run();
                    return null;
                }
        );
    }

    public void run(String resource, Runnable action) {
        this.run(List.of(resource), action);
    }

    public void run(Collection<String> resources, Runnable action) {
        this.write(
                resources,
                () -> {
                    action.run();
                    return null;
                }
        );
    }

    public void cleanupLocks() {
        long before = System.currentTimeMillis() - 172_800_000L;
        for (String key : locks.stale(before)) {
            this.run(
                    () -> locks.cleanup(key, before)
            );
        }
    }

    private static String digest(String resource) {
        try {
            return HexFormat
                    .of()
                    .formatHex(
                            MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(resource.getBytes(StandardCharsets.UTF_8))
                    );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

}
