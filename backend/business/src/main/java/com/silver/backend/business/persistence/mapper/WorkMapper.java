package com.silver.backend.business.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkMapper {

    @Select("SELECT COUNT(*) FROM works WHERE owner_id=#{id} AND status='PUBLISHED'")
    long publishedCount(@Param("id") String id);
}
