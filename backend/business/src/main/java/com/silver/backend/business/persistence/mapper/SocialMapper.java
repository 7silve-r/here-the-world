package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.BookmarkRow;
import com.silver.backend.business.persistence.CommentRow;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface SocialMapper {
    @Select("SELECT COUNT(*) FROM work_likes WHERE work_id=#{work}")
    long likes(@Param("work") String work);

    @Select("SELECT COUNT(*) FROM bookmarks WHERE work_id=#{work}")
    long bookmarks(@Param("work") String work);

    @Select("SELECT COUNT(*) FROM comments WHERE work_id=#{work}")
    long comments(@Param("work") String work);

    @Select("SELECT COUNT(*) FROM work_likes WHERE work_id=#{work} AND user_id=#{user}")
    long liked(@Param("work") String work, @Param("user") String user);

    @Select("SELECT COUNT(*) FROM bookmarks WHERE work_id=#{work} AND user_id=#{user}")
    long bookmarked(@Param("work") String work, @Param("user") String user);

    @Insert("INSERT INTO work_likes(user_id,work_id,created_at) VALUES(#{user},#{work},#{at})")
    void like(@Param("work") String work, @Param("user") String user, @Param("at") long at);

    @Delete("DELETE FROM work_likes WHERE work_id=#{work} AND user_id=#{user}")
    void unlike(@Param("work") String work, @Param("user") String user);

    @Insert("INSERT INTO bookmarks(id,user_id,work_id,created_at) VALUES(#{id},#{user},#{work},#{at})")
    void bookmark(
            @Param("id") String id,
            @Param("work") String work,
            @Param("user") String user,
            @Param("at") long at);

    @Delete("DELETE FROM bookmarks WHERE work_id=#{work} AND user_id=#{user}")
    void unbookmark(@Param("work") String work, @Param("user") String user);

    @Select("""
        SELECT b.id, b.work_id, b.created_at
        FROM bookmarks b
        JOIN works w ON w.id = b.work_id
        WHERE b.user_id = #{user}
          AND w.status = 'PUBLISHED'
          AND (
              b.created_at < #{at}
              OR (b.created_at = #{at} AND b.id < #{after})
          )
        ORDER BY b.created_at DESC, b.id DESC
        LIMIT #{limit}
    """)
    List<BookmarkRow> bookmarkPage(
            @Param("user") String user,
            @Param("at") long at,
            @Param("after") String after,
            @Param("limit") int limit);

    @Select("SELECT * FROM comments WHERE id=#{id}")
    CommentRow comment(@Param("id") String id);
}
