package com.silver.backend;

import com.silver.backend.business.system.Requests;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.JsonCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RequestValidationTest extends BackendTestSupport {
    @Autowired private Requests requests;
    @Autowired private JsonCodec json;

    @Test
    void preservesPassword() {
        String password = "  password123  ";
        var login = requests.decode("LoginInput", Map.of(
                "email", "user@example.test", "password", password), Login.class);
        assertEquals(password, login.password());
        assertEquals("user@example.test", login.email());
    }

    @Test
    void rejectsMissingOrExtra() {
        assertDetail("email", "REQUIRED", () ->
                requests.decode("LoginInput", Map.of("password", "password123"), Login.class));
        assertDetail("role", "NOT_ALLOWED", () -> requests.decode("LoginInput", Map.of(
                "email", "user@example.test", "password", "password123", "role", "admin"), Login.class));
    }

    @Test
    void patchFields() {
        Map<String, Object> input = Values.map("avatarAssetId", null);
        var patch = requests.patch("ProfilePatch", input, Profile.class);
        input.clear();

        assertEquals(Set.of("avatarAssetId"), patch.fields());
        assertNull(patch.value().avatarAssetId());
        assertFalse(patch.contains("bio"));
        assertThrows(UnsupportedOperationException.class, () -> patch.fields().add("bio"));
        assertDetail("", "REQUIRED", () -> requests.patch("ProfilePatch", Map.of(), Profile.class));
    }

    @Test
    void countsUnicode() {
        String name = "😀".repeat(40);
        var patch = requests.patch("ProfilePatch", Map.of("displayName", name), Profile.class);
        assertEquals(name, patch.value().displayName());
        assertDetail("displayName", "TOO_LONG", () ->
                requests.patch("ProfilePatch", Map.of("displayName", name + "😀"), Profile.class));
    }

    @Test
    void reportsArrayIndex() {
        Map<String, Object> input = Values.map(
                "kind", "IMAGE", "title", "照片", "body", "", "mediaAssetIds", List.of("valid", 42),
                "coverAssetId", null, "musicLinkId", null);
        assertDetail("mediaAssetIds[1]", "INVALID_FORMAT", () ->
                requests.decode("WorkInput", input, Work.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1.0000000000000001", "\"1\""})
    void rejectsNonInteger(String value) {
        assertDetail("sizeBytes", "INVALID_FORMAT", () -> size(value));
    }

    @Test
    void integerRange() {
        assertEquals(1L, size("1.0"));
        assertEquals(9_007_199_254_740_991L, size("9007199254740991"));
        assertDetail("sizeBytes", "OUT_OF_RANGE", () -> size("0"));
        assertDetail("sizeBytes", "OUT_OF_RANGE", () -> size("9007199254740992"));
    }

    private long size(String value) {
        return requests.decode("UploadCompleteInput", json.readMap("{\"sizeBytes\":" + value + "}"),
                Upload.class).sizeBytes();
    }

    private void assertDetail(String field, String reason, Runnable action) {
        ApiException error = assertThrows(ApiException.class, action::run);
        assertEquals(422, error.status);
        assertEquals(field, error.details.get(0).field());
        assertEquals(reason, error.details.get(0).reason());
    }

    record Login(String email, String password) {}
    record Profile(String displayName, String bio, String avatarAssetId) {}
    record Work(String kind, String title, String body, List<String> mediaAssetIds,
                String coverAssetId, String musicLinkId) {}
    record Upload(long sizeBytes) {}
}
