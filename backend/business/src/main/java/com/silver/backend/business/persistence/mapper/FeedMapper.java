package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.FeedRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FeedMapper {
    @Insert("INSERT INTO feed_sessions(id,cutoff,expires_at) VALUES(#{row.id},#{row.cutoff},#{row.expiresAt})")
    void insert(@Param("row") FeedRow row);

    @Insert("INSERT INTO feed_items(session_id,position,work_id) VALUES(#{session},#{position},#{work})")
    void item(
            @Param("session") String session,
            @Param("position") long position,
            @Param("work") String work);
}
