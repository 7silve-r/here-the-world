package com.silver.backend.business.media;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.*;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.UploadRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class MediaService {
    private static final Logger LOG = LoggerFactory.getLogger(MediaService.class);
    @Autowired
    private TransactionHelper tx;
    @Autowired
    private AssetMapper mapper;
    @Autowired
    private Crypto crypto;
    @Autowired
    private Views  views;
    @Autowired
    private FileStorage storage;
    @Autowired
    private TaskQueue taskQueue;
    @Autowired
    private MediaEngine engine;

    public record Download(Path file, String mime) {}

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
                    a.setCreatedAt(Values.now());
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

    private List<String> uploadLocks(UploadRow upload) {
        return List.of("30:asset:" + upload.getAssetId(), "40:upload:" + upload.getId());
    }

    public void transfer(String ticket, InputStream input) throws IOException {
        Map<String, Object> claim;
        try {
            claim = crypto.open("upload", ticket, "UPLOAD_EXPIRED");
        } catch (ApiException invalid) {
            throw new ApiException(410, "UPLOAD_EXPIRED", "上传票据无效或已过期");
        }
        if (!(claim.get("upload") instanceof String id) || id.isBlank() || id.length() > 64) {
            throw new ApiException(410, "UPLOAD_EXPIRED", "上传票据无效或已过期");
        }
        UploadRow hint = mapper.upload(id);
        if (hint == null) {
            throw new ApiException(404, "UPLOAD_NOT_FOUND", "上传不存在");
        }
        List<String> resources = uploadLocks(hint);
        UploadRow upload =
                tx.write(
                        resources,
                        () -> {
                            UploadRow u = mapper.upload(id);
                            if (u == null) {
                                throw new ApiException(404, "UPLOAD_NOT_FOUND", "上传不存在");
                            }
                            if (u.getExpiresAt() <= Values.now()) {
                                throw new ApiException(410, "UPLOAD_EXPIRED", "上传已过期");
                            }
                            if (!"CREATED".equals(u.getState())) {
                                throw new ApiException(409, "UPLOAD_ALREADY_COMPLETED", "上传正在接收或已结束");
                            }
                            u.setState("RECEIVING");
                            mapper.updateUpload(u);
                            return u;
                        });
        AssetRow a = mapper.asset(upload.getAssetId());
        try {
            long actual = storage.write(
                    a.getStorageKey(),
                    input,
                    Math.min(upload.getExpectedSize(), rule(a.getPurpose(), a.getMimeType()).maxBytes()));
            tx.run(
                    resources,
                    () -> {
                        UploadRow current = mapper.upload(id);
                        if (current == null
                                || !"RECEIVING".equals(current.getState())
                                || current.getExpiresAt() <= Values.now()) {
                            throw new ApiException(410, "UPLOAD_EXPIRED", "上传已取消或过期");
                        }
                        if (actual != current.getExpectedSize()) {
                            throw ApiException.invalid(
                                    "sizeBytes", "INVALID_FORMAT", "实际大小与申请大小不一致");
                        }
                        current.setState("UPLOADED");
                        mapper.updateUpload(current);
                        AssetRow uploaded = mapper.asset(current.getAssetId());
                        uploaded.setStatus("UPLOADED");
                        mapper.updateAsset(uploaded);
                    });
        } catch (IOException | RuntimeException failure) {
            try {
                tx.run(
                        resources,
                        () -> {
                            try {
                                storage.delete(a.getStorageKey());
                            } catch (IOException cleanup) {
                                failure.addSuppressed(cleanup);
                                LOG.warn("Upload file cleanup failed uploadId={}, assetId={}",
                                        id, a.getId(), cleanup);
                            }
                            UploadRow current = mapper.upload(id);
                            if (current != null && "RECEIVING".equals(current.getState())) {
                                current.setState("CREATED");
                                mapper.updateUpload(current);
                            }
                        });
            } catch (RuntimeException recovery) {
                if (recovery != failure) failure.addSuppressed(recovery);
                LOG.error("Upload state recovery failed uploadId={}, assetId={}", id, a.getId(), recovery);
            }
            throw failure;
        }
    }

    private UploadRow owned(String id, Viewer viewer) {
        UploadRow u = mapper.upload(id);
        if (u == null || !u.getOwnerId().equals(viewer.requiredUser())) {
            throw new ApiException(404, "UPLOAD_NOT_FOUND", "上传不存在");
        }
        return u;
    }

    public Ack cancel(String id, Viewer viewer) {
        UploadRow hint = owned(id, viewer);
        return tx.write(
                uploadLocks(hint),
                () -> {
                    UploadRow u = owned(id, viewer);
                    if ("COMPLETED".equals(u.getState())) {
                        throw new ApiException(409, "UPLOAD_ALREADY_COMPLETED", "上传已完成");
                    }
                    if (!Set.of("CANCELLED", "EXPIRED").contains(u.getState())) {
                        u.setState("CANCELLED");
                        u.setFinishedAt(Values.now());
                        mapper.updateUpload(u);
                        var a = mapper.asset(u.getAssetId());
                        a.setStatus("FAILED");
                        a.setFailureCode("UPLOAD_CANCELLED");
                        mapper.updateAsset(a);
                    }
                    return new Ack(true);
                });
    }

    private void discardOutput(String id) {
        for (String key :
                List.of("thumbnails/" + id + ".jpg", "processed/" + id + ".mp4", "processed/" + id + ".mp3")) {
            try {
                storage.delete(key);
            } catch (IOException cleanup) {
                LOG.warn("Media output cleanup failed outputId={}, key={}", id, key, cleanup);
            }
        }
    }

    public void process(Map<String, Object> payload) {
        AssetRow before = mapper.asset(payload.get("assetId").toString());
        if (before == null || !"PROCESSING".equals(before.getStatus())) {
            return;
        }
        String outputId = before.getId() + "-" + taskQueue.executionId();
        long started = System.nanoTime();
        try {
            MediaEngine.Info info = engine.inspect(before.getStorageKey());
            MediaEngine.Output out = engine.process(outputId, before.getStorageKey(), info);
            boolean accepted =
                    tx.write(
                            "30:asset:" + before.getId(),
                            () -> {
                                if (!taskQueue.ownsExecution()) {
                                    return false;
                                }
                                AssetRow a = mapper.asset(before.getId());
                                if (a != null && "PROCESSING".equals(a.getStatus())) {
                                    a.setPlaybackKey(out.playback());
                                    a.setThumbnailKey(out.thumbnail());
                                    a.setStatus("READY");
                                    a.setFailureCode(null);
                                    mapper.updateAsset(a);
                                    return true;
                                }
                                return false;
                            });
            if (!accepted) {
                LOG.warn("Media result discarded assetId={}, outputId={}", before.getId(), outputId);
                discardOutput(outputId);
            } else {
                LOG.info("Media processing completed assetId={}, outputId={}, elapsedMs={}",
                        before.getId(), outputId, (System.nanoTime() - started) / 1_000_000);
            }
        } catch (Exception failed) {
            try {
                boolean recorded = tx.write(
                    "30:asset:" + before.getId(),
                    () -> {
                        if (!taskQueue.ownsExecution()) {
                            return false;
                        }
                        AssetRow a = mapper.asset(before.getId());
                        if (a != null && "PROCESSING".equals(a.getStatus())) {
                            a.setStatus("FAILED");
                            a.setFailureCode("MEDIA_PROCESSING_FAILED");
                            mapper.updateAsset(a);
                            return true;
                        }
                        return false;
                    });
                if (recorded) {
                    LOG.error("Media processing failed assetId={}, outputId={}, elapsedMs={}",
                            before.getId(), outputId, (System.nanoTime() - started) / 1_000_000, failed);
                } else {
                    LOG.warn("Obsolete media failure discarded assetId={}, outputId={}", before.getId(), outputId);
                }
            } catch (RuntimeException recovery) {
                if (recovery != failed) failed.addSuppressed(recovery);
                throw new IllegalStateException("Could not record media processing failure", failed);
            } finally {
                discardOutput(outputId);
            }
        }
    }

    public Asset complete(String id, Viewer viewer, UploadCompleteInput in) throws IOException {
        UploadRow u = owned(id, viewer);
        AssetRow a = mapper.asset(u.getAssetId());
        if ("COMPLETED".equals(u.getState())) {
            return views.asset(a, viewer);
        }
        if (u.getExpiresAt() <= Values.now()) {
            throw new ApiException(410, "UPLOAD_EXPIRED", "上传已过期");
        }
        if (!"UPLOADED".equals(u.getState())) {
            throw ApiException.invalid("uploadId", "NOT_ALLOWED", "请先完成二进制上传");
        }
        if (storage.size(a.getStorageKey()) != in.sizeBytes() || in.sizeBytes() != u.getExpectedSize()) {
            throw ApiException.invalid("sizeBytes", "INVALID_FORMAT", "实际文件大小不一致");
        }
        MediaEngine.Info info;
        try {
            info = engine.inspect(a.getStorageKey());
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            LOG.warn("Upload media inspection failed uploadId={}, assetId={}", id, a.getId(), e);
            throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "媒体无法读取；音视频/WebP 需要 FFmpeg 与 FFprobe");
        }
        UploadRule allowed = rule(a.getPurpose(), info.mime());
        if (!allowed.mediaType().equals(a.getMediaType())) {
            throw new ApiException(422, "ASSET_TYPE_MISMATCH", "文件内容与声明类型不符");
        }
        return tx.write(
                uploadLocks(u),
                () -> {
                    UploadRow current = owned(id, viewer);
                    AssetRow row = mapper.asset(current.getAssetId());
                    if ("COMPLETED".equals(current.getState())) {
                        return views.asset(row, viewer);
                    }
                    if (!"UPLOADED".equals(current.getState()) || current.getExpiresAt() <= Values.now()) {
                        throw new ApiException(410, "UPLOAD_EXPIRED", "上传已取消或过期");
                    }
                    row.setMimeType(info.mime());
                    row.setWidth(info.width());
                    row.setHeight(info.height());
                    row.setDurationMs(info.durationMs());
                    row.setSizeBytes(in.sizeBytes());
                    row.setStatus("PROCESSING");
                    mapper.updateAsset(row);
                    current.setState("COMPLETED");
                    current.setFinishedAt(Values.now());
                    mapper.updateUpload(current);
                    taskQueue.enqueue("ASSET", Values.map("assetId", row.getId()));
                    return views.asset(row, viewer);
                });
    }

    public Asset get(String id, Viewer viewer) {
        AssetRow a = mapper.asset(id);
        if (a == null
            || (!Objects.equals(a.getOwnerId(), viewer.userId()) && !views.publicAsset(id))) {
            throw new ApiException(404, "ASSET_NOT_FOUND", "资产不存在");
        }
        return views.asset(a, viewer);
    }

    public Download download(String id, String variant, Viewer viewer, boolean privateMedia)
            throws IOException {
        AssetRow a = mapper.asset(id);
        if (a == null
                || !"READY".equals(a.getStatus())
                || (privateMedia
                ? !Objects.equals(a.getOwnerId(), viewer.requiredUser())
                : !views.publicAsset(a.getId()))) {
            throw new ApiException(404, "ASSET_NOT_FOUND", "资产不存在");
        }
        String key =
                switch (variant) {
                    case "file" -> a.getPlaybackKey() == null ? a.getStorageKey() : a.getPlaybackKey();
                    case "thumbnail" -> a.getThumbnailKey();
                    default -> null;
                };
        if (key == null) {
            throw new ApiException(404, "ASSET_NOT_FOUND", "文件不存在");
        }
        String mime =
                Objects.equals(key, a.getThumbnailKey()) ? "image/jpeg"
                        : key.endsWith(".mp4") ? "video/mp4"
                        : key.endsWith(".mp3") ? "audio/mpeg"
                        : a.getMimeType();
        Path file = storage.path(key);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            LOG.warn("Media file unavailable assetId={}, variant={}, privateMedia={}, key={}",
                    a.getId(),
                    variant,
                    privateMedia,
                    key);
            throw new ApiException(404, "ASSET_NOT_FOUND", "文件不可用");
        }
        LOG.debug(
                "Media file resolved assetId={}, variant={}, privateMedia={}",
                a.getId(),
                variant,
                privateMedia);
        return new Download(file, mime);
    }
}
