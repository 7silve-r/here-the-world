package com.silver.backend.business.dto;

import java.util.List;

public record CommentPage(List<Comment> items, String nextCursor, Boolean hasMore) {
}
