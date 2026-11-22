package com.silver.backend.business.work;

import com.silver.backend.business.Views;
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
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
}
