package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.WorkRow;
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
}
