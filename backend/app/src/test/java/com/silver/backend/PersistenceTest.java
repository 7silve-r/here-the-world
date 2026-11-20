package com.silver.backend;

import com.silver.backend.common.Values;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest extends BackendTestSupport {
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwords;

    @Test
    void migratesSchema() {
        assertEquals("1", flyway.info().current().getVersion().getVersion());
        var tables = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                """, String.class);
        assertEquals(Set.of("resource_locks", "users", "sessions", "verification_codes",
                "password_resets", "assets", "uploads", "music_links", "works", "work_assets",
                "work_likes", "bookmarks", "comments", "feed_sessions", "feed_items",
                "idempotency", "rate_limits", "background_tasks"), Set.copyOf(tables));
    }

    @Test
    void storesPasswordDigest() {
        String raw = "test-password-123";
        String hash = passwords.encode(raw);
        String id = Values.id();
        insertUser(id, id + "@example.test", "pw-" + id.substring(0, 12), hash);
        String stored = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, id);

        assertEquals(hash, stored);
        assertNotEquals(raw, stored);
        assertNotEquals(stored, passwords.encode(raw));
        assertTrue(passwords.matches(raw, stored));
        assertFalse(passwords.matches("different-password", stored));
    }

    @Test
    void enforcesUniqueIdentity() {
        String id = Values.id();
        String email = id + "@example.test";
        String username = "uq-" + id.substring(0, 12);
        insertUser(id, email, username, "fixture");

        assertThrows(DuplicateKeyException.class, () ->
                insertUser(Values.id(), email, username + "-other", "fixture"));
        assertThrows(DuplicateKeyException.class, () ->
                insertUser(Values.id(), "other-" + email, username, "fixture"));
    }

    private void insertUser(String id, String email, String username, String hash) {
        jdbc.update("""
                INSERT INTO users (id, email, username, password_hash, display_name, bio,
                  email_verified, theme, motion, auto_play_media, created_at)
                VALUES (?, ?, ?, ?, '测试', '', FALSE, 'SYSTEM', 'SYSTEM', TRUE, ?)
                """, id, email, username, hash, Values.now());
    }
}
