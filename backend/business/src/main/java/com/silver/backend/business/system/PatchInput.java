package com.silver.backend.business.system;

import java.util.Objects;
import java.util.Set;

public record PatchInput<T>(T value, Set<String> fields) {
    public PatchInput(T value, Set<String> fields) {
        this.value = Objects.requireNonNull(value, "Patch 数据对象不能为空");
        this.fields = Set.copyOf(fields);
    }

    public boolean contains(String field) {
        return fields.contains(field);
    }
}
