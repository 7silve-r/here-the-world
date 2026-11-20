package com.silver.backend.business.system;

import com.silver.backend.common.ApiException;
import com.silver.backend.common.Values;
import com.silver.backend.infrastructure.JsonCodec;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class Requests {
    private final Map<String, Object> schemas;
    private final JsonCodec json;

    @SuppressWarnings("unchecked")
    public Requests(JsonCodec json) throws IOException {
        this.json = json;
        try (InputStream stream =
                     getClass().getResourceAsStream("/openapi.json")) {
            Map<String, Object> spec =json.readMap(
                    new String(Objects.requireNonNull(stream).readAllBytes(),
                            StandardCharsets.UTF_8)
            );
            schemas = (Map<String, Object>)
                    ((Map<String, Object>) spec.get("components")).get("schemas");
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T decode(String schema,
                        Map<String, Object> input,
                        Class<T> type) {
        validate((Map<String, Object>) schemas.get(schema), input, "");
        return json.convert(input, type);
    }

    public <T> PatchInput<T> patch(String schema,
                                   Map<String, Object> input,
                                   Class<T> type) {
        return new PatchInput<>(
                decode(schema, input, type), input.keySet()
        );
    }

    @SuppressWarnings("unchecked")
    private void validate(Map<String, Object> schema,
                          Object value,
                          String path) {
        if (schema.containsKey("$ref")) {
            validate(
                    (Map<String, Object>) schemas.get(
                        schema.get("$ref")
                                .toString()
                                .replace("#/components/schemas/", "")
                    ),
                    value,
                    path
            );
            return;
        }
        if (schema.containsKey("anyOf")) {
            for (Object branch : (List<?>) schema.get("anyOf")) {
                try{
                    validate((Map<String, Object>) branch, value, path);
                    return;
                }catch (ApiException ignored){}
            }
            throw ApiException.invalid(path, "INVALID_FORMAT", "字段类型不正确");
        }
        String type = (String) schema.get("type");
        if ("null".equals(type)) {
            if (value != null) {
                throw ApiException.invalid(path, "NOT_ALLOWED", "字段必须为null");
            }
            return;
        }
        if (value == null) {
            throw ApiException.invalid(path, "REQUIRED", "字段不能为空");
        }
        if (schema.containsKey("enum") && !((List<?>) schema.get("enum")).contains(value)) {
            throw ApiException.invalid(path, "NOT_ALLOWED", "枚举值不正确");
        }
        if (schema.containsKey("const") && !Objects.equals(schema.get("const"), value)) {
            throw ApiException.invalid(path, "NOT_ALLOWED", "字段值不正确");
        }
        switch (type == null ? "" : type) {
            case "object" -> {
                if (!(value instanceof Map<?, ?>)) {
                    throw ApiException.invalid(path, "INVALID_FORMAT", "应为Json对象");
                }
                Map<String, Object> map = (Map<String, Object>) value;
                for (Object key : (List<?>) schema.getOrDefault("required", List.of())) {
                    if (!map.containsKey(key)) {
                        throw ApiException.invalid(
                                join(path, key.toString()), "REQUIRED", "缺少必填字段"
                        );
                    }
                }
                if (map.size() < ((Number) schema.getOrDefault("minProperties", 0)).intValue()) {
                    throw ApiException.invalid(path, "REQUIRED", "提交字段数过少");
                }
                Map<String, Object> fields = (Map<String, Object>) schema.get("properties");
                map.forEach(
                        (k ,v) -> {
                            if (fields.containsKey(k)) {
                                validate((Map<String, Object>) fields.get(k), v, join(path, k));
                            }
                            else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                                throw ApiException.invalid(
                                        join(path, k), "NOT_ALLOWED", "不支持的字段"
                                );
                            }
                        }
                );
            }
            case "string" -> {
                if (!(value instanceof String text)) {
                    throw ApiException.invalid(path, "INVALID_FORMAT", "应为字符串");
                }
                int length = Values.length(text);
                if (length < ((Number) schema.getOrDefault("minLength", 0)).intValue()) {
                    throw ApiException.invalid(path, "REQUIRED", "内容过短");
                }
                if (length > ((Number) schema.getOrDefault("maxLength", Integer.MAX_VALUE)).intValue()) {
                    throw ApiException.invalid(path, "TOO_LONG", "内容过长");
                }
                if (schema.containsKey("pattern") && !text.matches((String) schema.get("pattern"))) {
                    throw ApiException.invalid(path, "INVALID_FORMAT", "字段格式不正确");
                }
                if ("email".equals(schema.get("format"))) {
                    if (!text.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                        throw ApiException.invalid(path, "INVALID_FORMAT", "邮箱格式不正确");
                    }
                }
            }
            case "integer" -> {
                if (!(value instanceof Number)
                    || ((Number) value).doubleValue() != ((Number)value).longValue()) {
                    throw ApiException.invalid(path, "INVALID_FORMAT", "应为整数");
                }
                long n = ((Number) value).longValue();
                if (n < ((Number) schema.getOrDefault("minimum", Long.MIN_VALUE)).longValue()
                    || n > ((Number) schema.getOrDefault("maximum", Long.MAX_VALUE)).longValue()) {
                    throw ApiException.invalid(path, "OUT_OF_RANGE", "数字超出范围");
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)) {
                    throw ApiException.invalid(path, "INVALID_FORMAT", "应为布尔值");
                }
            }
            case "array" -> {
                if (!(value instanceof List<?> list)) {
                    throw ApiException.invalid(path, "INVALID_FORMAT", "应为数组");
                }
                if (list.size() > ((Number) schema.getOrDefault("maxItems", Integer.MAX_VALUE)).intValue()) {
                    throw ApiException.invalid(path, "OUT_OF_RANGE", "数组元素过多");
                }
                if (Boolean.TRUE.equals(schema.get("uniqueItems"))
                    && new HashSet<>(list).size() != list.size()) {
                    throw ApiException.invalid(path, "NOT_ALLOWED", "数组元素不能重复");
                }
                for (int i = 0; i < list.size(); i++) {
                    validate((Map<String, Object>) schema.get("items"), list.get(i), join(path, "[" + i + "]"));
                }
            }
            default -> {}
        }
    }

    private String join(String prefix, String field) {
        return prefix.isEmpty() ? field : prefix + "." + field;
    }

    public static String trimmed(String value, String field, boolean required) {
        String result = value.strip();
        if (required && result.isEmpty()) {
            throw ApiException.invalid(field, "REQUIRED", "内容不能为空");
        }
        return result;
    }
}
