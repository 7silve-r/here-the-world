package com.silver.backend.business.dto;

import java.util.List;

public record FeedPage(List<WorkCard> items, String nextCursor, Boolean hasMore, FeedSession feedSession) {
    public record FeedSession(String id, String expiresAt) {}
}