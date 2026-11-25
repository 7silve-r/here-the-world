package com.silver.backend.business.feed;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feed")
public class FeedController {
    @Autowired
    private QueryService service;

    @GetMapping
    public ResponseEntity<?> get(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.feed(RequestContext.viewer(req), cursor, limit));
    }
}
