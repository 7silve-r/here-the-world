package com.silver.backend.business.music;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import com.silver.backend.business.dto.MusicLinkInput;
import com.silver.backend.business.system.Requests;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/music-links")
public class MusicController {
    @Autowired
    private MusicService service;
    @Autowired
    private Requests requests;

    @PostMapping("/resolve")
    public ResponseEntity<?> resolve(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        MusicLinkInput input = requests.decode("MusicLinkInput", body, MusicLinkInput.class);
        return HttpReplies.ok(req, service.resolve(RequestContext.viewer(req), input));
    }
}
