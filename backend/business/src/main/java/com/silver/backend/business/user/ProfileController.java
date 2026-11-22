package com.silver.backend.business.user;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import com.silver.backend.business.dto.ProfilePatch;
import com.silver.backend.business.system.Requests;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ProfileController {
    @Autowired
    private ProfileService service;
    @Autowired
    private Requests requests;

    @GetMapping("/users/{userId}")
    public ResponseEntity<?> user(@PathVariable("userId") String id, HttpServletRequest req) {
        return HttpReplies.ok(req, service.user(id));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest req) {
        return HttpReplies.ok(req, service.me(RequestContext.viewer(req)));
    }

    @PatchMapping("/me")
    public ResponseEntity<?> patch(@RequestBody Map<String,Object> body, HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.patch(
                        RequestContext.viewer(req),
                        requests.patch("ProfilePatch", body, ProfilePatch.class)));
    }

    @GetMapping("/me/settings")
    public ResponseEntity<?> settings(HttpServletRequest req) {
        return HttpReplies.ok(req, service.settings(RequestContext.viewer(req)));
    }
}
