package com.silver.backend.business.interaction;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.*;
import com.silver.backend.business.feed.Pages;
import com.silver.backend.business.persistence.BookmarkRow;
import com.silver.backend.business.persistence.CommentRow;
import com.silver.backend.business.persistence.mapper.SocialMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InteractionService {
    @Autowired
    private SocialMapper socialMapper;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private TransactionHelper tx;
    @Autowired
    private Views views;
    @Autowired
    private Pages pages;

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

    public InteractionState bookmark(Viewer v, String work, boolean enabled) {
        return tx.write(
                "20:work:" + work,
                () -> {
                    String user = v.requiredUser();
                    views.interactable(work, v);
                    boolean exist = socialMapper.bookmarked(work, user) > 0;
                    if (enabled && !exist) {
                        socialMapper.bookmark(Values.id(), work, user, Values.now());
                    }
                    if (!enabled && exist) {
                        socialMapper.unbookmark(work, user);
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

    public WorkPage bookmarks(Viewer v, String cursor, Integer requested) {
        String user = v.requiredUser();
        int limit = pages.limit(requested);
        Pages.Position pos = pages.position(cursor, "/me/bookmarks", user, false);
        List<BookmarkRow> rows = socialMapper.bookmarkPage(user, pos.time(), pos.id(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) {
            rows = rows.subList(0, limit);
        }
        String next = null;
        if (more) {
            BookmarkRow last = rows.get(rows.size() - 1);
            next = pages.cursor("/me/bookmarks", user, last.getCreatedAt(), last.getId());
        }
        List<WorkCard> cards = rows.stream()
                .map(b -> workMapper.get(b.getWorkId()))
                .filter(w -> w != null && "PUBLISHED".equals(w.getStatus()))
                .map(w -> views.card(w, v))
                .toList();
        return new WorkPage(cards, next, more);
    }

    public CommentPage comments(Viewer v, String work, String cursor, Integer requested) {
        views.interactable(work, v);
        int limit = pages.limit(requested);
        String route = "/works/" + work + "/comments";
        Pages.Position pos = pages.position(cursor, "/me/comments", "", true);
        List<CommentRow> rows = socialMapper.commentPage(work, pos.time(), pos.id(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) {
            rows = rows.subList(0, limit);
        }
        String next = null;
        if (more) {
            CommentRow last = rows.get(rows.size() - 1);
            next = pages.cursor(route, "", last.getCreatedAt(), last.getId());
        }
        return new CommentPage(
                rows.stream().map(c -> views.comment(c, v)).toList(),
                next,
                more);
    }

    public Comment createComment(Viewer v, String work, CommentInput input) {
        return tx.write(
                "20:work:" + work,
                () -> {
                    views.interactable(work, v);
                    CommentRow c = new CommentRow();
                    c.setId(Values.id());
                    c.setWorkId(work);
                    c.setUserId(v.requiredUser());
                    c.setBody(Requests.trimmed(input.body(), "body", true));
                    c.setCreatedAt(Values.now());
                    socialMapper.insertComment(c);
                    return views.comment(c, v);
                })
    }
}
