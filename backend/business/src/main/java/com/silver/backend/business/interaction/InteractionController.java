package com.silver.backend.business.interaction;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InteractionController {
    @Autowired
    private InteractionService service;

    @PutMapping("/works/{workId}/like")
    public ResponseEntity<?> like(@PathVariable("workId") String workId, HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.like(RequestContext.viewer(req), workId, true));
    }

    @DeleteMapping("/works/{workId}/like")
    public ResponseEntity<?> unlike(@PathVariable("workId") String workId, HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.like(RequestContext.viewer(req), workId, false));
    }
}
