package com.silver.backend.business.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
