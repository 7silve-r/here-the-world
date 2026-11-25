package com.silver.backend.business.feed;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.WorkPage;
import com.silver.backend.business.persistence.FeedRow;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.business.persistence.mapper.FeedMapper;
import com.silver.backend.business.persistence.mapper.WorkMapper;
import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.common.Viewer;
import com.silver.backend.infrastructure.TransactionHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class QueryService {
    @Autowired
    private Pages pages;
    @Autowired
    private Views views;
    @Autowired
    private WorkMapper workMapper;
    @Autowired
    private FeedMapper feedMapper;
    @Autowired
    private TransactionHelper tx;

    public WorkPage list(Viewer v, String owner, boolean mine, String status, String cursor, Integer requested) {
        int limit = pages.limit(requested);
        if (mine) {
            v.requiredUser();
        }
        else {
            views.user(owner);
        }
        if (status != null && !Set.of("PUBLISHED", "DRAFT", "PROCESSING", "FAILED").contains(status)) {
            throw new ApiException(400, "BAD_REQUEST", "状态过滤值无效");
        }
        String route = mine ? "/me/works" : "/users/" + owner + "/works";
        String filter = owner + ":" + Objects.toString(status, "");
        Pages.Position pos = pages.position(cursor, route, filter, false);
        List<WorkRow> rows = mine ?
                workMapper.mine(owner, status, pos.time(), pos.id(), limit + 1) :
                workMapper.published(owner, pos.time(), pos.id(), limit + 1);
        boolean more = rows.size() > limit;
        if (more) {
            rows = rows.subList(0, limit);
        }
        String next = null;
        if (more) {
            WorkRow last = rows.get(rows.size() - 1);
            next = pages.cursor(route, filter, mine ? last.getCreatedAt() : last.getPublishedAt(), last.getId());
        }
        return new WorkPage(rows.stream().map(w -> views.card(w, v)).toList(), next, more);
    }

    private FeedRow createSession() {
        return tx.write(
                () -> {
                    var row = new FeedRow();
                    row.setId(Values.id());
                    row.setCutoff(Values.now());
                    row.setExpiresAt(Values.now() + 1_800_000);
                    feedMapper.insert(row);
                    var candidates = new ArrayList<>(workMapper.candidates(row.getCutoff()));
                    Collections.shuffle(candidates);
                    int count = Math.min(2000, candidates.size());
                    for (int i = 0; i < count; i++) {
                        feedMapper.item(row.getId(), i, candidates.get(i));
                    }
                    return row;
                }
        );
    }
}
