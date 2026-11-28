package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.MusicRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface MusicMapper {
    @Select("SELECT * FROM music_links WHERE id=#{id}")
    MusicRow get(@Param("id") String id);

    @Insert("""
        INSERT INTO music_links(id,owner_id,provider,source_url,title,artist,cover_url,metadata_status,created_at)
        VALUES(#{row.id},#{row.ownerId},#{row.provider},#{row.sourceUrl},#{row.title},#{row.artist},
               #{row.coverUrl},#{row.metadataStatus},#{row.createdAt})
    """)
    void insert(@Param("row") MusicRow row);
}
