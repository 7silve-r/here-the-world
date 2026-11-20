package com.silver.backend.business;

import com.silver.backend.common.Viewer;
import jakarta.servlet.http.HttpServletRequest;

public final class RequestContext {

    private RequestContext() {}

    public static Viewer viewer(HttpServletRequest request) {
        if (request.getAttribute("viewer") instanceof Viewer v) {
            return v;
        }
        return Viewer.guest();
    }
}
