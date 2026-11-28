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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
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

    @GetMapping("/assets/{assetId}")
    public ResponseEntity<?> get(@PathVariable("assetId") String assetId, HttpServletRequest req) {
        return HttpReplies.ok(
                req,
                service.get(assetId, RequestContext.viewer(req)));
    }

    private ResponseEntity<Resource> file(MediaService.Download file, boolean publicMedia)
            throws IOException {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mime()))
                .header(HttpHeaders.CACHE_CONTROL,
                        publicMedia ? "public, max-age=60, must-revalidate" : "private, no-store")
                .lastModified(Files.getLastModifiedTime(file.file()).toMillis())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file.file()));
    }

    @GetMapping("/media/public/assets/{assetId}/{variant}")
    public ResponseEntity<?> publicFile(
            @PathVariable("assetId")  String assetId,
            @PathVariable("variant") String variant)
            throws IOException {
        return file(
                service.download(assetId, variant, Viewer.guest(), false),
                true);
    }

    @GetMapping("/media/private/assets/{assetId}/{variant}")
    public ResponseEntity<?> privateFile(
            @PathVariable("assetId")  String assetId,
            @PathVariable("variant") String variant,
            HttpServletRequest req)
            throws IOException {
        return file(
                service.download(assetId, variant, RequestContext.viewer(req), true),
                false);
    }
}
