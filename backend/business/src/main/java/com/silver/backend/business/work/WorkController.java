package com.silver.backend.business.work;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WorkController {
    @Autowired
    WorkService service;

    @GetMapping("/works/{workId}")
    public ResponseEntity<?> get(@PathVariable("workId") String id, HttpServletRequest req) {
        return HttpReplies.reply(req, service.get(RequestContext.viewer(req), id));
    }
}
