package com.silver.backend.business.user;

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
public class ProfileController {
    @Autowired
    private ProfileService service;

    @GetMapping("/users/{userId}")
    public ResponseEntity<?> user(@PathVariable("userId") String id, HttpServletRequest req) {
        return HttpReplies.ok(req, service.user(id));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        return HttpReplies.ok(req, service.me(RequestContext.viewer(req)));
    }
}
