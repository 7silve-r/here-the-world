package com.silver.backend.business.dto;

import java.util.List;

public record WorkPage(List<WorkCard> items, String nextCursor, Boolean hasMore) {
}
