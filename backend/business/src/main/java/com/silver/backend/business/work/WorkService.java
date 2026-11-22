package com.silver.backend.business.work;

import com.silver.backend.business.Views;
import com.silver.backend.business.persistence.WorkRow;
import com.silver.backend.common.Reply;
import com.silver.backend.common.Viewer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WorkService {
    @Autowired
    private Views views;

    public Reply get(Viewer viewer, String id) {
        WorkRow w = views.visible(id, viewer);
        return Reply.work(200, views.detail(w, viewer), w.getVersion());
    }
}
