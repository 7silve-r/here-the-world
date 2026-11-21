package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.AssetRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetMapper {

    @Select("SELECT * FROM assets WHERE id=#{id}")
    AssetRow asset(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM users WHERE avatar_asset_id=#{id}")
    long avatarReferences(@Param("id") String id);

    @Select("""
        SELECT COUNT(*) 
        FROM works AS w 
        WHERE (w.cover_asset_id=#{id}
            OR EXISTS (
                   SELECT 1
                   FROM work_assets wa
                   WHERE wa.work_id = w.id
                     AND wa.asset_id = #{id}))
    """)
    long publicWorkReferences(@Param("id") String id);
}
