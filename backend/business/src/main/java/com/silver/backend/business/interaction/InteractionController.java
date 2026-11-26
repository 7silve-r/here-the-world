package com.silver.backend.business.interaction;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import com.silver.backend.business.dto.CommentInput;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.Reply;
import com.silver.backend.common.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class InteractionController {
    @Autowired
    private InteractionService service;
    @Autowired
    private Requests requests;
    @Autowired
    private RequestControl control;

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

    @PutMapping("/works/{workId}/bookmark")
    public ResponseEntity<?> bookmark(@PathVariable("workId") String workId, HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.bookmark(RequestContext.viewer(req), workId, true));
    }

    @DeleteMapping("/works/{workId}/bookmark")
    public ResponseEntity<?> unbookmark(@PathVariable("workId") String workId, HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.bookmark(RequestContext.viewer(req), workId, false));
    }

    @GetMapping("/me/bookmarks")
    public ResponseEntity<?> bookmarks(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.bookmarks(RequestContext.viewer(req), cursor, limit));
    }

    @GetMapping("/works/{workId}/comments")
    public ResponseEntity<?> comments(
            @PathVariable("workId") String workId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.comments(RequestContext.viewer(req), workId, cursor, limit));
    }

    @PostMapping("/works/{workId}/comments")
    public ResponseEntity<?> createComment(
            @PathVariable("workId") String workId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest req) {
        CommentInput input = requests.decode("CommentInput", body, CommentInput.class);
        Viewer v = RequestContext.viewer(req);
        return HttpReplies.reply(
                req,
                control.idempotency(
                        v.requiredUser(),
                        "/works/" + workId + "/comments",
                        key,
                        body,
                        () -> Reply.of(201, service.createComment(v, workId, input))));
    }
}
