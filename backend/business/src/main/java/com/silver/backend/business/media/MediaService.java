package com.silver.backend.business.media;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.UploadInput;
import com.silver.backend.business.dto.UploadRule;
import com.silver.backend.business.dto.UploadTicket;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.UploadRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.Crypto;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MediaService {
    @Autowired
    private TransactionHelper tx;
    @Autowired
    private AssetMapper mapper;
    @Autowired
    private Crypto crypto;
    @Autowired
    private Views  views;

    public List<UploadRule> rules() {
        return List.of(
                new UploadRule(
                        "WORK_MEDIA",
                        "IMAGE",
                        List.of("image/jpeg", "image/png", "image/webp", "image/gif"),
                        20L * 1024 * 1024),
                new UploadRule(
                        "WORK_MEDIA",
                        "VIDEO",
                        List.of("video/mp4", "video/webm"),
                        500L * 1024 * 1024),
                new UploadRule(
                        "WORK_MEDIA",
                        "AUDIO",
                        List.of("audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/ogg", "audio/flac"),
                        100L * 1024 * 1024),
                new UploadRule(
                        "WORK_COVER",
                        "IMAGE",
                        List.of("image/jpeg", "image/png", "image/webp"),
                        10L * 1024 * 1024),
                new UploadRule(
                        "AVATAR",
                        "IMAGE",
                        List.of("image/jpeg", "image/png", "image/webp"),
                        5L * 1024 * 1024));
    }

    private UploadRule rule(String purpose, String mime) {
        return rules().stream()
                .filter(r -> r.purpose().equals(purpose) && r.allowedMimeTypes().contains(mime))
                .findFirst()
                .orElseThrow(() -> new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "用途不支持该媒体格式"));
    }

    public UploadTicket create(Viewer viewer, UploadInput in) {
        return tx.write(
                () -> {
                    UploadRule r = rule(in.purpose(), in.contentType());
                    if (in.sizeBytes() > r.maxBytes()) {
                        throw new ApiException(413, "FILE_TOO_LARGE", "文件过大");
                    }
                    var a = new AssetRow();
                    a.setId(Values.id());
                    a.setOwnerId(viewer.requiredUser());
                    a.setPurpose(in.purpose());
                    a.setMediaType(r.mediaType());
                    a.setStatus("CREATED");
                    a.setMimeType(in.contentType());
                    a.setSizeBytes(in.sizeBytes());
                    a.setCleanedAt(Values.now());
                    a.setStorageKey("originals/" + a.getId());
                    mapper.insertAsset(a);
                    var u = new UploadRow();
                    u.setId(Values.id());
                    u.setAssetId(a.getId());
                    u.setOwnerId(a.getOwnerId());
                    u.setState("CREATED");
                    u.setExpiresAt(Values.now() + 900_000);
                    u.setCreatedAt(Values.now());
                    u.setExpectedSize(in.sizeBytes());
                    mapper.insertUpload(u);
                    String ticket = crypto.ticket("upload", Values.map("upload", u.getId()), 900_000);
                    return new UploadTicket(
                            u.getId(),
                            a.getId(),
                            views.baseUrl() + "/media/upload?ticket=" + ticket,
                            "PUT",
                            Map.of("Content-Type", in.contentType()),
                            Values.time(u.getExpiresAt()),
                            r.maxBytes());
                });
    }
}
