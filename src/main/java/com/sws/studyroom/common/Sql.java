package com.sws.studyroom.common;

import java.sql.*;
import java.util.*;

public final class Sql {
    private Sql() {}
    public static PreparedStatement query(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        try { bind(p, values); return p; } catch (SQLException ex) { p.close(); throw ex; }
    }
    public static void bind(PreparedStatement p, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
    }
    public static int update(Connection c, String sql, Object... values) throws SQLException {
        try (var p = query(c, sql, values)) { return p.executeUpdate(); }
    }
    public static long insert(Connection c, String sql, Object... values) throws SQLException {
        try (var p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, values); p.executeUpdate();
            try (var r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("缺少生成的编号"); return r.getLong(1); }
        }
    }
    public static Map<String, Object> one(Connection c, String sql, Object... values) throws SQLException {
        var rows = rows(c, sql, values); return rows.isEmpty() ? null : rows.get(0);
    }
    public static List<Map<String, Object>> rows(Connection c, String sql, Object... values) throws SQLException {
        var rows = new ArrayList<Map<String, Object>>();
        try (var p = query(c, sql, values); var r = p.executeQuery()) {
            var meta = r.getMetaData();
            while (r.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = r.getObject(i); String name = meta.getColumnLabel(i);
                    if (value instanceof Timestamp t) value = t.toLocalDateTime().toString();
                    else if (value != null && name.endsWith("_id")) value = value.toString();
                    row.put(name, value);
                }
                rows.add(row);
            }
        }
        return rows;
    }
}
