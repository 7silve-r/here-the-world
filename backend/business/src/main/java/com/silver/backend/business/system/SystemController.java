package com.silver.backend.business.system;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.dto.Capabilities;
import com.silver.backend.business.media.MediaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    @Autowired
    private MediaService service;

    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities(HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                new Capabilities(
                        service.rules(),
                        9L,
                        120L,
                        20000L,
                        2000L,
                        600L,
                        60L,
                        List.of()));
    }
}
