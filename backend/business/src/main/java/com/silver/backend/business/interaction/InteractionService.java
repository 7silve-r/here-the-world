package com.silver.backend.business.interaction;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.InteractionState;
import com.silver.backend.business.persistence.mapper.SocialMapper;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class InteractionService {
    @Autowired
    private SocialMapper socialMapper;
    @Autowired
    private TransactionHelper tx;
    @Autowired
    private Views views;

    //enabled 表示“这次请求希望最终处于什么状态”：
    //- true：确保已经点赞或收藏
    //- false：确保没有点赞或收藏
    public InteractionState like(Viewer v, String work, boolean enabled) {
        return tx.write(
                "20:work:" + work,
                () -> {
                    String user = v.requiredUser();
                    views.interactable(work, v);
                    boolean exist = socialMapper.liked(work, user) > 0;
                    if (enabled && !exist) {
                        socialMapper.like(work, user, Values.now());
                    }
                    if (!enabled && exist) {
                        socialMapper.unlike(work, user);
                    }
                    return new InteractionState(
                            work,
                            socialMapper.liked(work, user) > 0,
                            socialMapper.bookmarked(work, user) > 0,
                            socialMapper.likes(work),
                            socialMapper.bookmarks(work));
                }
        );
    }
}
