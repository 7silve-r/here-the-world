package com.silver.backend.infrastructure;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;


@Component
public class JsonCodec {

    @Autowired
    private JsonMapper mapper;

    public String write(Object object) {
        return mapper.writeValueAsString(object);
    }

    public <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public <T> T convert(Object object, Class<T> type) {
        return mapper.convertValue(object, type);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readMap(String json) {
        return read(json, Map.class);
    }

    public Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((k, v) ->
                    sorted.put(k.toString(), canonical(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::canonical).toList();
        }
        return value;
    }
}
