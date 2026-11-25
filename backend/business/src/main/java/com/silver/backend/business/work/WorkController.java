package com.silver.backend.business.work;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import com.silver.backend.business.dto.WorkInput;
import com.silver.backend.business.feed.QueryService;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class WorkController {
    @Autowired
    WorkService service;
    @Autowired
    Requests requests;
    @Autowired
    RequestControl control;
    @Autowired
    private QueryService queryService;

    @GetMapping("/works/{workId}")
    public ResponseEntity<?> get(@PathVariable("workId") String id, HttpServletRequest req) {
        return HttpReplies.reply(req, service.get(RequestContext.viewer(req), id));
    }

    @PostMapping("/works")
    public ResponseEntity<?> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest req
    ) {
        WorkInput input = requests.decode("WorkInput", body, WorkInput.class);
        Viewer v = RequestContext.viewer(req);
        return HttpReplies.reply(
                req,
                control.idempotency(v.requiredUser(), "/works", key, body, () -> service.create(v, input)));
    }

    @PutMapping("/works/{workId}")
    public ResponseEntity<?> save(
            @PathVariable("workId") String workId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        return  HttpReplies.reply(
                req,
                service.save(RequestContext.viewer(req),
                        workId,
                        ifMatch,
                        requests.decode("WorkInput", body, WorkInput.class)));
    }

    @PostMapping("/works/{workId}/publish")
    public ResponseEntity<?> publish(
            @PathVariable("workId") String workId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest req) {
        return  HttpReplies.reply(
                req,
                service.publish(RequestContext.viewer(req), workId, ifMatch));
    }

    @DeleteMapping("/works/{workId}")
    public ResponseEntity<?> delete(
            @PathVariable("workId") String workId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest req) {
        return  HttpReplies.reply(
                req,
                service.delete(RequestContext.viewer(req), workId, ifMatch));
    }

    @GetMapping("/users/{userId}/works")
    public ResponseEntity<?> userWorks(
            @PathVariable String userId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                queryService.list(RequestContext.viewer(req), userId, false, null, cursor, limit));
    }

    @GetMapping("/me/works")
    public ResponseEntity<?> myWorks(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String status,
            HttpServletRequest req) {
        Viewer v = RequestContext.viewer(req);
        return HttpReplies.ok(
                req,
                queryService.list(v, v.requiredUser(), true, status, cursor, limit));
    }
}
