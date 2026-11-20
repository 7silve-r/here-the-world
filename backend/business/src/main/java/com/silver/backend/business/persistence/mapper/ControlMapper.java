package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.IdempotencyRow;
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

    @Select("SELECT * FROM idempotency WHERE id=#{id}")
    IdempotencyRow idem(@Param("id") String id);

    @Select("SELECT * FROM idempotency WHERE id=#{id} FOR UPDATE")
    IdempotencyRow lockedIdem(@Param("id") String id);

    @Delete("DELETE FROM idempotency WHERE id=#{id}")
    void deleteIdem(@Param("id") String id);

    @Insert("""
        INSERT INTO idempotency(id,body_hash,response_json,expires_at,lease_until,lease_token)
        VALUES(#{row.id},#{row.bodyHash},#{row.responseJson},#{row.expiresAt},#{row.leaseUntil},#{row.leaseToken})
    """)
    void insertIdem(@Param("row")  IdempotencyRow row);

    @Update("""
        UPDATE idempotency 
        SET response_json=#{json} 
        WHERE id=#{id} 
          AND lease_token=#{token}
          AND response_json IS NULL
    """)
    int complete(@Param("id") String id, @Param("token") String token, @Param("json")  String json);

    @Delete("""
        DELETE FROM idempotency 
        WHERE id=#{id} 
          AND lease_token=#{token} 
          AND response_json IS NULL
    """)
    void abandon(@Param("id") String id, @Param("token") String token);
}
