package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class BookmarkRow {
    private String id;
    private String workId;
    private long createdAt;
}
