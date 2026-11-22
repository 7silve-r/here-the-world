package com.silver.backend.business.dto;

public record MusicLink(
        String id,
        String provider,
        String sourceUrl,
        String title,
        String artist,
        String coverUrl,
        String metadataStatus,
        Playback playback) {
    public record Playback(String mode, String embedUrl) {}
}
