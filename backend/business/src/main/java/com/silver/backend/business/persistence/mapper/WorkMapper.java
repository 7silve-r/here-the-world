package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.WorkRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
