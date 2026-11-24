package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.WorkRow;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface WorkMapper {

    @Select("SELECT COUNT(*) FROM works WHERE owner_id=#{id} AND status='PUBLISHED'")
    long publishedCount(@Param("id") String id);

    @Select("SELECT * FROM works WHERE id=#{id}")
    WorkRow get(@Param("id") String id);

    @Select("SELECT asset_id FROM work_assets WHERE work_id=#{id} ORDER BY position")
    List<String> assets(@Param("id") String id);

    @Insert("""
        INSERT INTO works(id,owner_id,kind,status,version,title,body,
                          cover_asset_id,music_link_id,failure_code,publish_task_id,
                          created_at,updated_at,published_at)
        VALUES(#{row.id},#{row.ownerId},#{row.kind},#{row.status},#{row.version},#{row.title},#{row.body},
               #{row.coverAssetId},#{row.musicLinkId},#{row.failureCode},#{row.publishTaskId},
               #{row.createdAt},#{row.updatedAt},#{row.publishedAt})
    """)
    void insert(@Param("row") WorkRow row);

    @Delete("DELETE FROM work_assets WHERE work_id=#{id}")
    void clearAssets(@Param("id") String id);

    @Insert("INSERT INTO work_assets(work_id,asset_id,position) VALUES(#{work},#{asset},#{position})")
    void bind(@Param("work") String work, @Param("asset") String asset, @Param("position") int position);

    @Update("""
        UPDATE works
        SET status=#{row.status},version=#{row.version},title=#{row.title},
            body=#{row.body},cover_asset_id=#{row.coverAssetId},music_link_id=#{row.musicLinkId},
            failure_code=#{row.failureCode},publish_task_id=#{row.publishTaskId},
            updated_at=#{row.updatedAt},published_at=#{row.publishedAt}
        WHERE id=#{row.id} AND version=#{expected}
    """)
    int update(@Param("row") WorkRow row, @Param("expected") long expected);
}
