package com.silver.backend.business.work;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.Ack;
import com.silver.backend.business.dto.WorkInput;
import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.AssetMapper;
import com.silver.backend.business.persistence.mapper.MusicMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Reply;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.TaskQueue;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class WorkService {
    @Autowired
    private Views views;
    @Autowired
    private TransactionHelper tx;
    @Autowired
    private AssetMapper assetMapper;
    @Autowired
    private MusicMapper musicMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    TaskQueue taskQueue;

    public Reply get(Viewer viewer, String id) {
        WorkRow w = views.visible(id, viewer);
        return Reply.work(200, views.detail(w, viewer), w.getVersion());
    }

    private ArrayList<String> assetLocks(WorkInput input) {
        ArrayList<String> resources =  new ArrayList<>();
        for (String asset : input.mediaAssetIds()) {
            resources.add("30:asset:" + asset);
        }
        if (input.coverAssetId() != null) {
            resources.add("30:asset:" + input.coverAssetId());
        }
        return resources;
    }

    private void checkAsset(String id, String user, String purpose, String type, boolean publishing) {
        AssetRow a = assetMapper.asset(id);
        if (a == null || !a.getOwnerId().equals(user)) {
            throw new ApiException(422, "ASSET_OWNERSHIP_INVALID", "资产不属于当前用户");
        }
        if (!a.getPurpose().equals(purpose) || !a.getMediaType().equals(type)) {
            throw new ApiException(422, "ASSET_TYPE_MISMATCH", "资产用途或类型不匹配");
        }
        if (a.getCleanedAt() != null || a.getStorageKey() == null) {
            throw new ApiException(422, "ASSET_NOT_READY", "资源已回收，请重新上传");
        }
        if (publishing && !Set.of("READY", "UPLOADED", "PROCESSING").contains(a.getStatus())) {
            throw new ApiException(422, "ASSET_NOT_READY", "请先完成上传；处理失败的资源需要更换");
        }
    }

    private void validate(Viewer viewer, WorkInput in, boolean publishing) {
        Requests.trimmed(in.title(), "title", true);
        String user = viewer.requiredUser();
        int n = in.mediaAssetIds().size();
        if (Set.of("TEXT", "MUSIC_LINK").contains(in.kind()) && n != 0) {
            throw ApiException.invalid("mediaAssetIds", "NOT_ALLOWED", "该类型不允许主媒体文件");
        }
        if (Set.of("VIDEO", "AUDIO").contains(in.kind()) && n > 1) {
            throw ApiException.invalid("mediaAssetIds", "OUT_OF_RANGE", "该类型只允许一个主媒体");
        }
        if (!"MUSIC_LINK".equals(in.kind()) && in.musicLinkId() != null) {
            throw ApiException.invalid("musicLinkId", "NOT_ALLOWED", "该类型不允许音乐链接");
        }
        if (publishing) {
            if ("TEXT".equals(in.kind()) && in.body().strip().isEmpty()) {
                throw ApiException.invalid("body", "REQUIRED", "文字作品需要正文");
            }
            if (Set.of("IMAGE", "VIDEO", "AUDIO").contains(in.kind()) && n == 0) {
                throw ApiException.invalid("mediaAssetIds", "REQUIRED", "请选择主媒体");
            }
            if ("MUSIC_LINK".equals(in.kind()) && in.musicLinkId() == null) {
                throw ApiException.invalid("musicLinkId", "REQUIRED", "请选择音乐链接");
            }
        }
        for (String id : in.mediaAssetIds()) {
            checkAsset(id, user, "WORK_MEDIA", in.kind(), publishing);
        }
        if (in.coverAssetId() != null) {
            checkAsset(in.coverAssetId(), user, "WORK_COVER", "IMAGE", publishing);
        }
        if (in.musicLinkId() != null) {
            var m = musicMapper.get(in.musicLinkId());
            if (m == null || !m.getOwnerId().equals(user)) {
                throw new ApiException(422, "ASSET_OWNERSHIP_INVALID", "音乐链接不属于当前用户");
            }
        }
    }

    private void bind(String id, List<String> refs) {
        workMapper.clearAssets(id);
        for (int i = 0; i < refs.size(); i++) {
            workMapper.bind(id, refs.get(i), i);
        }
    }

    public Reply create(Viewer viewer, WorkInput input) {
        return tx.write(
                assetLocks(input),
                () -> {
                    validate(viewer, input, false);
                    WorkRow w = new WorkRow();
                    w.setId(Values.id());
                    w.setOwnerId(viewer.requiredUser());
                    w.setKind(input.kind());
                    w.setStatus("DRAFT");
                    w.setVersion(1);
                    w.setTitle(Requests.trimmed(input.title(), "title", true));
                    w.setBody(input.body().strip());
                    w.setCoverAssetId(input.coverAssetId());
                    w.setMusicLinkId(input.musicLinkId());
                    w.setCreatedAt(Values.now());
                    w.setUpdatedAt(w.getCreatedAt());
                    workMapper.insert(w);
                    bind(w.getId(), input.mediaAssetIds());
                    return Reply.work(201, views.detail(w, viewer), w.getVersion());
                });
    }

    private long parseVersion(String ifMatch) {
        if (ifMatch == null) {
            throw new ApiException(428, "PRECONDITION_REQUIRED", "需要 If-Match");
        }
        if (!ifMatch.matches("\"v[1-9][0-9]*\"")) {
            throw new ApiException(400, "BAD_REQUEST", "If-Match 格式错误");
        }
        try {
            return Long.parseLong(ifMatch.substring(2,  ifMatch.length() - 1));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "BAD_REQUEST", "If-Match 超出范围");
        }
    }

    private void match(WorkRow w, String ifMatch) {
        if (parseVersion(ifMatch) != w.getVersion()) {
            throw new ApiException(412, "VERSION_CONFLICT", "作品已发生变化，请重新读取");
        }
    }

    private void change(WorkRow w) {
        long old = w.getVersion();
        w.setVersion(old + 1);
        w.setUpdatedAt(Values.now());
        if (workMapper.update(w, old) != 1) {
            throw new ApiException(412, "VERSION_CONFLICT", "作品版本冲突");
        }
    }

    public Reply save(Viewer viewer, String id, String ifMatch, WorkInput input) {
        var resources = assetLocks(input);
        resources.add("20:work:" + id);
        return tx.write(
                resources,
                () -> {
                    WorkRow w = views.owned(id, viewer);
                    match(w, ifMatch);
                    if (!w.getKind().equals(input.kind())) {
                        throw new ApiException(409, "WORK_KIND_IMMUTABLE", "作品类型创建后不可改变");
                    }
                    if (!Set.of("DRAFT", "FAILED").contains(w.getStatus())) {
                        throw new ApiException(409, "WORK_NOT_EDITABLE", "作品当前不可编辑");
                    }
                    validate(viewer, input, false);
                    w.setTitle(Requests.trimmed(input.title(), "title", true));
                    w.setBody(input.body().strip());
                    w.setCoverAssetId(input.coverAssetId());
                    w.setMusicLinkId(input.musicLinkId());
                    w.setStatus("DRAFT");
                    w.setFailureCode(null);
                    w.setPublishTaskId(null);
                    change(w);
                    bind(w.getId(), input.mediaAssetIds());
                    return  Reply.work(200, views.detail(w, viewer), w.getVersion());
                }
        );
    }

    private List<AssetRow> references(WorkInput input) {
        var list = new ArrayList<AssetRow>();
        for (String id : input.mediaAssetIds()) {
            list.add(assetMapper.asset(id));
        }
        if (input.coverAssetId() != null) {
            list.add(assetMapper.asset(input.coverAssetId()));
        }
        return list;
    }

    public Reply publish(Viewer viewer, String id, String ifMatch) {
        return tx.write(
                "20:work:" + id,
                () -> {
                    WorkRow w = views.owned(id, viewer);
                    match(w, ifMatch);
                    if (Set.of("PROCESSING", "PUBLISHED").contains(w.getStatus())) {
                        return Reply.work(
                                "PROCESSING".equals(w.getStatus()) ? 202 : 200, views.detail(w, viewer), w.getVersion());
                    }
                    if (!"DRAFT".equals(w.getStatus())) {
                        throw new ApiException(409, "WORK_NOT_EDITABLE", "请先保存修正后的草稿");
                    }
                    WorkInput input = new WorkInput(
                            w.getKind(),
                            w.getTitle(),
                            w.getBody(),
                            workMapper.assets(id),
                            w.getCoverAssetId(),
                            w.getMusicLinkId());
                    tx.lock(assetLocks(input));
                    validate(viewer, input, true);
                    List<AssetRow> refs = references(input);
                    boolean pending = refs.stream().anyMatch(a -> !"READY".equals(a.getStatus()));
                    if (pending) {
                        w.setStatus("PROCESSING");
                        w.setPublishTaskId(Values.id());
                        taskQueue.enqueue("PUBLISH",
                                Values.map("work", w.getId(), "job", w.getPublishTaskId()));
                    }
                    else {
                        w.setStatus("PUBLISHED");
                        w.setPublishedAt(Values.now());
                    }
                    change(w);
                    return Reply.work(pending ? 202 : 200, views.detail(w, viewer), w.getVersion());
                }
        );
    }

    public void finishPublish(Map<String, Object> payload) {
        tx.run(
                "20:work:" + payload.get("work"),
                () -> {
                    if (!taskQueue.ownsExecution()) return;
                    WorkRow w = workMapper.get((String) payload.get("work"));
                    if (w == null
                            || !"PROCESSING".equals(w.getStatus())
                            || !Objects.equals(w.getPublishTaskId(), payload.get("job"))) return;
                   WorkInput input = new WorkInput(
                           w.getKind(), w.getTitle(), w.getBody(),
                           workMapper.assets(w.getId()), w.getCoverAssetId(), w.getMusicLinkId());
                   tx.lock(assetLocks(input));
                   List<AssetRow> refs = references(input);
                   if (refs.stream().anyMatch(a -> a == null || "FAILED".equals(a.getStatus()))) {
                       w.setStatus("FAILED");
                       w.setFailureCode("MEDIA_PROCESSING_FAILED");
                   }
                   else if (refs.stream().anyMatch(a -> !"READY".equals(a.getStatus()))) {
                        throw new TaskQueue.Pending();
                   }
                   else {
                       w.setStatus("PUBLISHED");
                       w.setPublishedAt(Values.now());
                       w.setFailureCode(null);
                   }
                   change(w);
                }
        );
    }

    public Reply delete(Viewer viewer, String id, String ifMatch) {
        return tx.write(
                "20:work:" + id,
                () -> {
                    long version = parseVersion(ifMatch);
                    WorkRow w = views.owned(id, viewer);
                    if (w.getVersion() != version) {
                        throw new ApiException(412, "VERSION_CONFLICT", "作品已发生变化，请重新读取");
                    }
                    workMapper.deleteLikes(w.getId());
                    workMapper.deleteBookmarks(w.getId());
                    workMapper.deleteComments(w.getId());
                    workMapper.clearAssets(w.getId());
                    if (workMapper.delete(id, version) != 1) {
                        throw new ApiException(412, "VERSION_CONFLICT", "作品版本冲突");
                    }
                    return Reply.ok(new Ack(true));
                }
        );
    }
}
