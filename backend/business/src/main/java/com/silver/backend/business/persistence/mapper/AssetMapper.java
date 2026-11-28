package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.AssetRow;
import com.silver.backend.business.persistence.UploadRow;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AssetMapper {

    @Select("SELECT * FROM assets WHERE id=#{id}")
    AssetRow asset(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM users WHERE avatar_asset_id=#{id}")
    long avatarReferences(@Param("id") String id);

    @Select("""
        SELECT COUNT(*) 
        FROM works AS w 
        WHERE w.status='PUBLISHED' AND (w.cover_asset_id=#{id}
            OR EXISTS (
                   SELECT 1
                   FROM work_assets wa
                   WHERE wa.work_id = w.id
                     AND wa.asset_id = #{id}))
    """)
    long publicWorkReferences(@Param("id") String id);

    @Insert("""
        INSERT INTO 
            assets(id,owner_id,purpose,
                   media_type,status,mime_type,
                   storage_key,playback_key,thumbnail_key,
                   failure_code,size_bytes,created_at,
                   width,height,duration_ms)
            VALUES(#{row.id},#{row.ownerId},#{row.purpose},
                   #{row.mediaType},#{row.status},#{row.mimeType},
                   #{row.storageKey},#{row.playbackKey},#{row.thumbnailKey},
                   #{row.failureCode},#{row.sizeBytes},#{row.createdAt},
                   #{row.width},#{row.height},#{row.durationMs})
    """)
    void insertAsset(@Param("row") AssetRow row);

    @Insert("""
        INSERT INTO 
            uploads(id,asset_id,owner_id,state,
                    expires_at,created_at,expected_size)
            VALUES(#{row.id},#{row.assetId},#{row.ownerId},#{row.state},
                   #{row.expiresAt},#{row.createdAt},#{row.expectedSize})
    """)
    void insertUpload(@Param("row") UploadRow row);

    @Select("SELECT * FROM uploads WHERE id=#{id}")
    UploadRow upload(@Param("id") String id);

    @Update("UPDATE uploads SET state=#{row.state},finished_at=#{row.finishedAt} WHERE id=#{row.id}")
    void updateUpload(@Param("row") UploadRow row);
}
