package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.SessionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuthMapper {
    @Select("SELECT * FROM sessions WHERE id=#{id}")
    SessionRow session(@Param("id") String id);
}
