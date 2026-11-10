package com.silver.backend.business.persistence.mapper;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ControlMapper {

    @Select("SELECT hits FROM rate_limits WHERE id=#{id} AND expires_at>#{now}")
    Integer rate(@Param("id") String id, @Param("now") long now);

    @Delete("DELETE FROM rate_limits WHERE id=#{id}")
    void deleteRate(@Param("id") String id);

    @Insert("INSERT INTO rate_limits(id, hits, expires_at) VALUES(#{id},1,#{expiry})")
    void insertRate(@Param("id") String id, @Param("expiry") long expiry);

    @Update("UPDATE rate_limits SET hits=hits+1 WHERE id=#{id}")
    void incrementRate(@Param("id") String id);
}
