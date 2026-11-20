package com.silver.backend.business.persistence.mapper;

import com.silver.backend.business.persistence.CodeRow;
import com.silver.backend.business.persistence.SessionRow;
import com.silver.backend.business.persistence.UserRow;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AuthMapper {
    @Select("SELECT * FROM sessions WHERE id=#{id}")
    SessionRow session(@Param("id") String id);

    @Select("SELECT * FROM users WHERE email=#{email}")
    UserRow email(@Param("email") String email);

    @Select("SELECT * FROM users WHERE username=#{username}")
    UserRow username(@Param("username") String username);

    @Insert("""
        INSERT INTO 
            users(id,email,username,password_hash,display_name,bio,
                  avatar_asset_id,email_verified,theme,motion,auto_play_media,created_at)
        VALUES (#{user.id},#{user.email},#{user.username},#{user.passwordHash},#{user.displayName},#{user.bio},
                #{user.avatarAssetId},#{user.emailVerified},#{user.theme},#{user.motion},#{user.autoPlayMedia},#{user.createdAt})
    """)
    void insertUser(UserRow user);

    @Select("SELECT * FROM verification_codes WHERE user_id=#{id}")
    CodeRow code(@Param("id") String id);

    @Delete("DELETE FROM verification_codes WHERE user_id=#{id}")
    void deleteCode(@Param("id") String id);

    @Insert("""
        INSERT INTO 
            verification_codes(user_id,code_hash,expires_at,sent_at,attempts)
        VALUES (#{row.userId},#{row.codeHash},#{row.expiresAt},#{row.sentAt},#{row.attempts})
    """)
    void insertCode(CodeRow row);

    @Select("SELECT * FROM users WHERE id=#{id}")
    UserRow user(@Param("id") String id);

    @Update("UPDATE verification_codes SET attemps=attemps+1 WHERE user_id=#{id}")
    void attempt(@Param("id") String id);

    @Update("""
        UPDATE users 
        SET password_hash=#{user.passwordHash},display_name=#{user.displayName},bio=#{user.bio},
            avatar_asset_id=#{user.avatarAssetId},email_verified=#{user.emailVerified},theme=#{user.theme},
            motion=#{user.motion},auto_play_media=#{user.autoPlayMedia}
        WHERE id=#{user.id}
    """)
    void updateUser(UserRow user);

}
