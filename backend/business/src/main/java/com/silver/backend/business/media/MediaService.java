package com.silver.backend.business.media;

import com.silver.backend.business.dto.UploadRule;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MediaService {

    public List<UploadRule> rules() {
        return List.of(
                new UploadRule(
                        "WORK_MEDIA",
                        "IMAGE",
                        List.of("image/jpeg", "image/png", "image/webp", "image/gif"),
                        20L * 1024 * 1024),
                new UploadRule(
                        "WORK_MEDIA",
                        "VIDEO",
                        List.of("video/mp4", "video/webm"),
                        500L * 1024 * 1024),
                new UploadRule(
                        "WORK_MEDIA",
                        "AUDIO",
                        List.of("audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/ogg", "audio/flac"),
                        100L * 1024 * 1024),
                new UploadRule(
                        "WORK_COVER",
                        "IMAGE",
                        List.of("image/jpeg", "image/png", "image/webp"),
                        10L * 1024 * 1024),
                new UploadRule(
                        "AVATAR",
                        "IMAGE",
                        List.of("image/jpeg", "image/png", "image/webp"),
                        5L * 1024 * 1024));
    }
}
