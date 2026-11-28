package com.silver.backend.business.media;

import com.silver.backend.business.HttpReplies;
import com.silver.backend.business.RequestContext;
import com.silver.backend.business.dto.UploadCompleteInput;
import com.silver.backend.business.dto.UploadInput;
import com.silver.backend.business.system.RequestControl;
import com.silver.backend.business.system.Requests;
import com.silver.backend.common.Reply;
import com.silver.backend.common.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MediaController {
    @Autowired
    private MediaService service;
    @Autowired
    private Requests requests;
    @Autowired
    private RequestControl control;

    @PostMapping("/uploads")
    public ResponseEntity<?> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest req) {
        UploadInput in = requests.decode("UploadInput", body, UploadInput.class);
        Viewer v = RequestContext.viewer(req);
        return HttpReplies.reply(
                req,
                control.idempotency(
                        v.requiredUser(),
                        "/uploads",
                        key,
                        body,
                        () -> Reply.of(201, service.create(v, in))));
    }

    @PutMapping("/media/upload")
    public ResponseEntity<?> transfer(@RequestParam String ticket, HttpServletRequest req)
            throws IOException {
        service.transfer(ticket, req.getInputStream());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/uploads/{uploadId}")
    public ResponseEntity<?> cancel(@PathVariable("uploadId") String uploadId, HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.cancel(uploadId, RequestContext.viewer(req)));
    }

    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<?> complete(
            @PathVariable("uploadId") String uploadId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req)
            throws IOException {
        return HttpReplies.reply(
                req,
                Reply.of(
                        202,
                        service.complete(
                                uploadId,
                                RequestContext.viewer(req),
                                requests.decode("UploadCompleteInput", body, UploadCompleteInput.class))));
    }
}
