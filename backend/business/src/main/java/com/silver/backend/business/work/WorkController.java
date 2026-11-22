package com.silver.backend.business.work;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import com.silver.backend.business.dto.WorkInput;
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
                control.idempotency(v.requireUser(), "/works", key, body, () -> service.create(v, input)));
    }
}
