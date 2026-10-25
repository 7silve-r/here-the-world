package com.silver.backend.business.persistence;

import lombok.Data;

@Data
public class CommentRow {
    private String id,
            workId,
            userId,
            body;
    private long createdAt;
}
