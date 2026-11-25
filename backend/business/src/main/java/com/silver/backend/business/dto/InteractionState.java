package com.silver.backend.business.dto;

public record InteractionState(String workId, Boolean liked, Boolean bookmarked, Long likeCount, Long bookmarkCount) {
}
