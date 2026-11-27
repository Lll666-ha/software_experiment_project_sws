package com.sws.studyroom.common;

import java.util.List;
import java.util.Map;

public record ListPage(int page, int size) {
    public static ListPage parse(String page, String size) {
        int index = page == null || page.isBlank() ? 1 : Integer.parseInt(page);
        int limit = size == null || size.isBlank() ? 20 : Integer.parseInt(size);
        if (index < 1 || index > 10000 || !List.of(20, 50, 100).contains(limit))
            throw new IllegalArgumentException("页码无效，或每页条数不是 20、50、100");
        return new ListPage(index, limit);
    }

    public int offset() { return (page - 1) * size; }

    public Map<String, Object> result(List<Map<String, Object>> items, Number total) {
        return Map.of("items", items, "total", total, "page", page, "page_size", size);
    }
}
