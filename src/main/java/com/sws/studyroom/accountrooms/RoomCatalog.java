package com.sws.studyroom.accountrooms;

import com.sws.studyroom.common.ListPage;
import com.sws.studyroom.common.SessionIdentity;
import com.sws.studyroom.common.Sql;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class RoomCatalog {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String STATUS = """
        CASE WHEN r.listing_status='UNLISTED' THEN 'UNLISTED'
             WHEN r.maintenance_status='MAINTENANCE' THEN 'MAINTENANCE'
             WHEN EXISTS (SELECT 1 FROM slot_lock s JOIN booking b ON b.booking_id=s.booking_id
                          WHERE s.room_id=r.room_id AND s.slot_start<? AND s.slot_end>?
                          AND (b.booking_status<>'PENDING_PAYMENT' OR b.pay_deadline>?)) THEN 'OCCUPIED'
             WHEN ? < TIMESTAMP(DATE(?),TIME(SUBSTRING_INDEX(r.opening_hours,'-',1)))
                  OR ? > TIMESTAMP(DATE(?),TIME(SUBSTRING_INDEX(r.opening_hours,'-',-1))) THEN 'CLOSED'
             ELSE 'AVAILABLE' END AS availability
        """;

    private RoomCatalog() {}

    static Map<String, Object> search(Connection c, HttpServletRequest request, SessionIdentity admin)
            throws SQLException {
        ListPage page = ListPage.parse(request.getParameter("page"), request.getParameter("page_size"));
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        LocalDateTime start = now, end = now.plusSeconds(1);
        String rawStart = param(request, "start_time"), rawEnd = param(request, "end_time");
        if (!rawStart.isEmpty() || !rawEnd.isEmpty()) {
            try {
                start = LocalDateTime.parse(rawStart);
                end = LocalDateTime.parse(rawEnd);
            } catch (DateTimeException ex) {
                throw new IllegalArgumentException("请填写合法的开始和结束时间", ex);
            }
            if (!start.isBefore(end) || !start.toLocalDate().equals(end.minusNanos(1).toLocalDate()))
                throw new IllegalArgumentException("请选择同一天内有效的时段");
        }
        var args = new ArrayList<Object>(List.of(end, start, now, start, start, end, start));
        StringBuilder sql = new StringBuilder("SELECT r.room_id,r.room_name,r.location,r.capacity,r.opening_hours,"
                + "r.listing_status,r.maintenance_status,r.manager_user_id,p.has_projector,p.has_whiteboard,"
                + "p.has_outlets,p.usage_rules,p.panorama_url," + STATUS
                + " FROM room r LEFT JOIN room_profile p ON p.room_id=r.room_id WHERE 1=1");
        if (admin == null) sql.append(" AND r.listing_status='LISTED'");
        else if (admin.role() == SessionIdentity.Role.ROOM_ADMIN) {
            sql.append(" AND r.manager_user_id=?");
            args.add(admin.userId());
        }
        contains(sql, args, "r.room_name", param(request, "name"));
        contains(sql, args, "r.location", param(request, "location"));
        String keyword = param(request, "keyword");
        if (!keyword.isEmpty()) {
            sql.append(" AND (LOCATE(?,r.room_name)>0 OR LOCATE(?,r.location)>0)");
            args.add(keyword); args.add(keyword);
        }
        String capacity = param(request, "min_capacity");
        if (!capacity.isEmpty()) {
            int minimum = Integer.parseInt(capacity);
            if (minimum < 1 || minimum > 10000) throw new IllegalArgumentException("人数无效");
            sql.append(" AND r.capacity>=?"); args.add(minimum);
        }
        for (String equipment : List.of("has_projector", "has_whiteboard", "has_outlets")) {
            String requested = param(request, equipment);
            if (requested.isEmpty()) continue;
            if (!requested.equals("1")) throw new IllegalArgumentException("设备筛选值无效");
            sql.append(" AND p.").append(equipment).append("=TRUE");
        }
        String status = param(request, "availability");
        String outer = " FROM (" + sql + ") catalog";
        if (!status.isEmpty()) {
            if (!List.of("AVAILABLE","OCCUPIED","MAINTENANCE","CLOSED","UNLISTED").contains(status))
                throw new IllegalArgumentException("房间状态无效");
            outer += " WHERE availability=?"; args.add(status);
        }
        String order = switch (param(request, "sort")) {
            case "", "id" -> "room_id";
            case "capacity" -> "capacity,room_id";
            case "location" -> "location,room_id";
            case "name" -> "room_name,room_id";
            default -> throw new IllegalArgumentException("排序方式无效");
        };
        Number total = (Number) Sql.one(c, "SELECT COUNT(*) AS total" + outer, args.toArray()).get("total");
        args.add(page.size()); args.add(page.offset());
        var items = Sql.rows(c, "SELECT *" + outer + " ORDER BY " + order + " LIMIT ? OFFSET ?", args.toArray());
        return page.result(items,total);
    }

    static void saveProfile(Connection c, HttpServletRequest request, long roomId) throws SQLException {
        if (request.getParameter("profile_present") == null) return;
        String rules = request.getParameter("usage_rules");
        String panorama = request.getParameter("panorama_url");
        if (rules != null && rules.length() > 500) throw new IllegalArgumentException("使用说明最多 500 字");
        if (panorama != null && !panorama.isBlank()) {
            try {
                var uri = java.net.URI.create(panorama);
                if (panorama.length() > 512 || !"https".equalsIgnoreCase(uri.getScheme())
                        || uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("全景照片须填写有效的 HTTPS 图片链接");
            }
        }
        Sql.update(c, """
            INSERT INTO room_profile(room_id,has_projector,has_whiteboard,has_outlets,usage_rules,panorama_url)
            VALUES (?,?,?,?,?,?) AS requested
            ON DUPLICATE KEY UPDATE has_projector=requested.has_projector,
                has_whiteboard=requested.has_whiteboard,has_outlets=requested.has_outlets,
                usage_rules=requested.usage_rules,panorama_url=requested.panorama_url
            """, roomId, "1".equals(request.getParameter("has_projector")),
                "1".equals(request.getParameter("has_whiteboard")), "1".equals(request.getParameter("has_outlets")),
                blankToNull(rules),blankToNull(panorama));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String param(HttpServletRequest request, String key) {
        String value = request.getParameter(key);
        if (value == null) return "";
        if (value.length() > 160) throw new IllegalArgumentException("检索条件过长");
        return value.trim();
    }

    private static void contains(StringBuilder sql, List<Object> args, String column, String value) {
        if (value.isEmpty()) return;
        sql.append(" AND LOCATE(?,").append(column).append(")>0");
        args.add(value);
    }
}
