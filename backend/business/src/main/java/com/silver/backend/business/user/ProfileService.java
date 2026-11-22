package com.silver.backend.business.user;

import com.silver.backend.business.Views;
import com.silver.backend.business.dto.Me;
import com.silver.backend.business.dto.PublicUser;
import com.silver.backend.common.Viewer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProfileService {
    @Autowired
    private Views  views;

    public PublicUser user(String id) {
        return views.user(id);
    }

    public Me me(Viewer viewer) {
        return views.me(viewer.requireUser());
    }
}
