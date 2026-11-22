package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.MusicRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MusicMapper {
    @Select("SELECT * FROM music_links WHERE id=#{id}")
    MusicRow get(@Param("id") String id);
}
