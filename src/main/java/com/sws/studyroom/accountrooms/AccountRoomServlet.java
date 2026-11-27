package com.sws.studyroom.accountrooms;

import com.sws.studyroom.common.Database;
import com.sws.studyroom.common.ModuleContracts;
import com.sws.studyroom.common.SessionIdentity;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

@WebServlet(value = "/api/account-rooms/*", loadOnStartup = 1)
public class AccountRoomServlet extends HttpServlet implements ModuleContracts.RoomAccess {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,32}");
    private static final Pattern HOURS = Pattern.compile("(?:[01]\\d|2[0-3]):[0-5]\\d-(?:[01]\\d|2[0-3]):[0-5]\\d");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CSRF = AccountRoomServlet.class.getName() + ".csrf";
    private static final int LOGIN_ATTEMPTS = 5;

    @Override
    public void init() throws ServletException {
        getServletContext().setAttribute(ModuleContracts.RoomAccess.class.getName(), this);
    }

    @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        serve(req, res, false);
    }

    @Override protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        serve(req, res, true);
    }

    private void serve(HttpServletRequest req, HttpServletResponse res, boolean write) throws IOException {
        req.setCharacterEncoding("UTF-8");
        res.setCharacterEncoding("UTF-8");
        res.setContentType("application/json;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        String path = Optional.ofNullable(req.getPathInfo()).orElse("/");
        try {
            if (!write && "/token".equals(path)) {
                output(res, 200, "{\"csrf\":\"" + json(token(req)) + "\"}");
                return;
            }
            if (write && !same(token(req), req.getParameter("csrf"))) {
                throw new Failure(403, "页面令牌失效，请刷新后重试");
            }
            if (!write && "/session".equals(path)) {
                output(res, 200, session(req));
                return;
            }
            try (Connection connection = Database.open()) {
                connection.setAutoCommit(false);
                try {
                    String body = write ? change(connection, req, path) : read(connection, req, path);
                    connection.commit();
                    if (req.getAttribute("signedIdentity") instanceof SessionIdentity signed) SessionIdentity.signIn(req, signed);
                    output(res, req.getAttribute("loginStatus") instanceof Integer status ? status : 200, body);
                } catch (SQLException | GeneralSecurityException | RuntimeException ex) {
                    connection.rollback();
                    throw ex;
                }
            }
        } catch (Failure ex) {
            output(res, ex.status, message(ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            output(res, 400, message(ex.getMessage()));
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1062) output(res, 409, message("用户名、联系方式或房间已经存在"));
            else {
                log("用户与房间模块数据库操作失败", ex);
                output(res, 500, message("数据库操作失败"));
            }
        } catch (GeneralSecurityException ex) {
            log("密码摘要失败", ex);
            output(res, 500, message("服务器安全组件不可用"));
        }
    }

    private String read(Connection c, HttpServletRequest r, String path) throws SQLException {
        return switch (path) {
            case "/rooms" -> rooms(c, r, false);
            case "/admin/rooms/impact" -> roomImpact(c,r);
            case "/admin/rooms" -> { requireAdmin(r); yield rooms(c, r, true); }
            case "/catalog" -> com.sws.studyroom.common.Json.encode(RoomCatalog.search(c, r, null));
            case "/catalog/detail" -> {
                if (SessionIdentity.current(r).isEmpty()) throw new Failure(401, "请先登录");
                long roomId = longNumber(required(r, "room_id"), 1, "房间编号");
                var room = RoomCatalog.detail(c, r, roomId);
                if (room == null) throw new Failure(404, "该研讨间已下架或不存在");
                yield com.sws.studyroom.common.Json.encode(room);
            }
            case "/admin/catalog" -> com.sws.studyroom.common.Json.encode(RoomCatalog.search(c, r, requireAdmin(r)));
            default -> throw new Failure(404, "接口不存在");
        };
    }

    private String change(Connection c, HttpServletRequest r, String path)
            throws SQLException, GeneralSecurityException {
        return switch (path) {
            case "/register" -> register(c, r);
            case "/register/code" -> issueCode(r, "register", contact(r));
            case "/login" -> login(c, r);
            case "/logout" -> { SessionIdentity.signOut(r); yield message("已退出登录"); }
            case "/password-reset/request" -> requestReset(c, r);
            case "/password-reset/confirm" -> confirmReset(c, r);
            case "/admin/rooms/save" -> saveRoom(c, r);
            case "/admin/rooms/status" -> updateStatus(c, r);
            default -> throw new Failure(404, "接口不存在");
        };
    }

    private String register(Connection c, HttpServletRequest r) throws SQLException, GeneralSecurityException {
        String username = required(r, "username"), password = confirmedPassword(r, "password");
        var contact = contact(r);
        if (!USERNAME.matcher(username).matches()) throw new IllegalArgumentException("用户名须为3至32位字母、数字或下划线");
        ContactCodes.consume(r.getSession(false), "register", contact, required(r, "code"));
        String salt = randomHex(16);
        try (PreparedStatement p = c.prepareStatement(
                "INSERT INTO `user`(username,phone,password_salt,password_hash,role,account_status) " +
                        "VALUES (?,?,?,?, 'USER','ACTIVE')", Statement.RETURN_GENERATED_KEYS)) {
            bind(p, username, contact.email() ? null : contact.value(), salt, hash(password, salt));
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("未取得新用户编号");
                long userId = keys.getLong(1);
                if (contact.email()) {
                    try (var email = c.prepareStatement("INSERT INTO account_email(user_id,email) VALUES (?,?)")) {
                        bind(email, userId, contact.value());
                        email.executeUpdate();
                    }
                }
                r.setAttribute("signedIdentity", new SessionIdentity(userId, SessionIdentity.Role.USER));
            }
        }
        return message("注册成功并已登录");
    }

    private String login(Connection c, HttpServletRequest r) throws SQLException, GeneralSecurityException {
        String login = required(r, "username"), password = required(r, "password");
        if (login.length() > 254 || password.length() > 72)
            throw new Failure(401, "账号或密码错误");
        try (PreparedStatement p = c.prepareStatement("""
                SELECT u.user_id,u.password_salt,u.password_hash,u.role,u.account_status
                FROM `user` u LEFT JOIN account_email e ON e.user_id=u.user_id
                WHERE u.username=? OR u.phone=? OR e.email=?
                ORDER BY (e.email IS NOT NULL AND e.email=? OR u.phone IS NOT NULL AND u.phone=?) DESC,u.user_id
                LIMIT 1 FOR UPDATE
                """)) {
            bind(p, login, login, login, login, login);
            try (ResultSet row = p.executeQuery()) {
                if (!row.next() || row.getString("password_salt") == null)
                    throw new Failure(401, "账号或密码错误");
                long user = row.getLong("user_id");
                var now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                int failures = 0;
                try (var guard = c.prepareStatement(
                        "SELECT failures,locked_until FROM account_login_guard WHERE user_id=?")) {
                    bind(guard, user);
                    try (ResultSet state = guard.executeQuery()) {
                        if (state.next()) {
                            var until = state.getObject("locked_until", LocalDateTime.class);
                            if (until != null && until.isAfter(now))
                                return loginFailure(r, 423, "账号已临时锁定，请在15分钟后重试，或找回密码");
                            if (until == null) failures = state.getInt("failures");
                        }
                    }
                }
                if (!same(row.getString("password_hash"), hash(password, row.getString("password_salt")))) {
                    int remaining = LOGIN_ATTEMPTS - ++failures;
                    try (var guard = c.prepareStatement("""
                            INSERT INTO account_login_guard(user_id,failures,locked_until) VALUES (?,?,?)
                            ON DUPLICATE KEY UPDATE failures=?,locked_until=?
                            """)) {
                        var until = remaining == 0 ? now.plusMinutes(15) : null;
                        bind(guard, user, failures, until, failures, until);
                        guard.executeUpdate();
                    }
                    return remaining == 0
                            ? loginFailure(r, 423, "尝试次数已用完，账号锁定15分钟")
                            : loginFailure(r, 401, "账号或密码错误，还可尝试 " + remaining + " 次");
                }
                if (!"ACTIVE".equals(row.getString("account_status"))) throw new Failure(403, "账号已停用");
                clearLoginGuard(c, user);
                SessionIdentity.Role role;
                try { role = SessionIdentity.Role.valueOf(row.getString("role")); }
                catch (IllegalArgumentException ex) { throw new SQLException("用户角色无效", ex); }
                r.setAttribute("signedIdentity", new SessionIdentity(user, role));
            }
        }
        return message("登录成功");
    }

    private static String loginFailure(HttpServletRequest r, int status, String message) {
        r.setAttribute("loginStatus", status);
        return message(message);
    }

    private static void clearLoginGuard(Connection c, long user) throws SQLException {
        try (var p = c.prepareStatement("DELETE FROM account_login_guard WHERE user_id=?")) {
            bind(p, user);
            p.executeUpdate();
        }
    }

    private static ContactCodes.Contact contact(HttpServletRequest r) {
        return ContactCodes.parse(required(r, r.getParameter("contact") == null ? "phone" : "contact"));
    }

    private static String issueCode(HttpServletRequest r, String purpose, ContactCodes.Contact contact) {
        String code = ContactCodes.issue(r.getSession(true), purpose, contact);
        return "{\"message\":\"模拟验证码已生成，5分钟内有效\",\"demo_code\":\"" + code + "\"}";
    }

    private static long resetUser(Connection c, HttpServletRequest r, ContactCodes.Contact contact, boolean lock) throws SQLException {
        String query = "SELECT u.user_id FROM `user` u LEFT JOIN account_email e ON e.user_id=u.user_id"
                + " WHERE u.username=? AND u.account_status='ACTIVE' AND "
                + (contact.email() ? "e.email=?" : "u.phone=?") + (lock ? " FOR UPDATE" : "");
        try (var p = c.prepareStatement(query)) {
            bind(p, required(r, "username"), contact.value());
            try (var row = p.executeQuery()) {
                if (!row.next()) throw new Failure(404, "用户名与绑定联系方式不匹配");
                return row.getLong(1);
            }
        }
    }

    private String requestReset(Connection c, HttpServletRequest r) throws SQLException {
        var contact = contact(r);
        resetUser(c, r, contact, false);
        return issueCode(r, "reset", contact);
    }

    private String confirmReset(Connection c, HttpServletRequest r) throws SQLException, GeneralSecurityException {
        var contact = contact(r);
        String password = confirmedPassword(r, "new_password");
        long userId = resetUser(c, r, contact, true);
        ContactCodes.consume(r.getSession(false), "reset", contact, required(r, "code"));
        String salt = randomHex(16);
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE `user` SET password_salt=?,password_hash=? WHERE user_id=?")) {
            bind(p, salt, hash(password, salt), userId);
            p.executeUpdate();
        }
        clearLoginGuard(c, userId);
        return message("密码重置成功");
    }

    private String rooms(Connection c, HttpServletRequest r, boolean admin) throws SQLException {
        int capacity = number(r.getParameter("min_capacity"), 0, 10000, "最少人数");
        String keyword = Optional.ofNullable(r.getParameter("keyword")).orElse("").trim();
        if (keyword.length() > 80) throw new IllegalArgumentException("关键词不能超过80个字符");
        SessionIdentity identity = SessionIdentity.current(r).orElse(null);
        StringBuilder sql = new StringBuilder("SELECT room_id,room_name,location,capacity,opening_hours," +
                "listing_status,maintenance_status,manager_user_id FROM room WHERE capacity>=?");
        if (!admin) sql.append(" AND listing_status='LISTED' AND maintenance_status='NORMAL'");
        if (!keyword.isBlank()) sql.append(" AND (room_name LIKE ? OR location LIKE ?)");
        if (admin && identity.role() == SessionIdentity.Role.ROOM_ADMIN) sql.append(" AND manager_user_id=?");
        sql.append(" ORDER BY room_id");
        StringBuilder out = new StringBuilder("{\"rooms\":[");
        try (PreparedStatement p = c.prepareStatement(sql.toString())) {
            int i = 1; p.setInt(i++, capacity);
            if (!keyword.isBlank()) { p.setString(i++, "%" + keyword + "%"); p.setString(i++, "%" + keyword + "%"); }
            if (admin && identity.role() == SessionIdentity.Role.ROOM_ADMIN) p.setLong(i, identity.userId());
            try (ResultSet rows = p.executeQuery()) {
                boolean first = true;
                while (rows.next()) {
                    if (!first) out.append(','); first = false;
                    Object manager = rows.getObject("manager_user_id");
                    out.append("{\"room_id\":\"").append(rows.getLong("room_id"))
                       .append("\",\"room_name\":\"").append(json(rows.getString("room_name")))
                       .append("\",\"location\":\"").append(json(rows.getString("location")))
                       .append("\",\"capacity\":").append(rows.getInt("capacity"))
                       .append(",\"opening_hours\":\"").append(json(rows.getString("opening_hours")))
                       .append("\",\"listing_status\":\"").append(rows.getString("listing_status"))
                       .append("\",\"maintenance_status\":\"").append(rows.getString("maintenance_status"))
                       .append("\",\"manager_user_id\":").append(manager == null ? "null" : "\"" + rows.getLong("manager_user_id") + "\"").append('}');
                }
            }
        }
        return out.append("]}").toString();
    }

    private String saveRoom(Connection c, HttpServletRequest r) throws SQLException {
        SessionIdentity identity = requireAdmin(r);
        long id = longNumber(r.getParameter("room_id"), 0, "房间编号");
        String name = limited(required(r, "room_name"), 80, "房间名称");
        String location = limited(required(r, "location"), 160, "位置");
        int capacity = number(r.getParameter("capacity"), 1, 10000, "容量");
        String hours = required(r, "opening_hours");
        if (!HOURS.matcher(hours).matches()) throw new IllegalArgumentException("开放时间格式应为 HH:mm-HH:mm");
        if (!java.time.LocalTime.parse(hours.substring(0,5)).isBefore(java.time.LocalTime.parse(hours.substring(6)))) throw new IllegalArgumentException("开放时间必须早于关闭时间");
        Long manager = identity.role() == SessionIdentity.Role.ROOM_ADMIN ? identity.userId() : optionalLong(r.getParameter("manager_user_id"));
        if (manager != null) {
            try (var p=c.prepareStatement("SELECT role FROM `user` WHERE user_id=? AND account_status='ACTIVE'")) {
                p.setLong(1,manager);
                try(var rows=p.executeQuery()) {
                    if(!rows.next() || !Set.of("ROOM_ADMIN","SUPER_ADMIN").contains(rows.getString(1)))
                        throw new IllegalArgumentException("负责人必须是可用的管理员账号");
                }
            }
        }
        if (id == 0) {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO room(room_name,location,capacity,opening_hours,manager_user_id) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                bind(p, name, location, capacity, hours, manager); p.executeUpdate();
                try (ResultSet keys = p.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("缺少新房间编号");
                    long newRoomId = keys.getLong(1);
                    RoomCatalog.saveProfile(c,r,newRoomId);
                    return "{\"message\":\"房间新增成功\",\"room_id\":\"" + newRoomId + "\"}";
                }
            }
        }
        String scope = identity.role() == SessionIdentity.Role.ROOM_ADMIN ? " AND manager_user_id=?" : "";
        try (PreparedStatement p = c.prepareStatement("UPDATE room SET room_name=?,location=?,capacity=?,opening_hours=?,manager_user_id=? WHERE room_id=?" + scope)) {
            bind(p, name, location, capacity, hours, manager, id);
            if (!scope.isEmpty()) p.setLong(7, identity.userId());
            if (p.executeUpdate() != 1) throw new Failure(404, "房间不存在或不在管理范围内");
        }
        RoomCatalog.saveProfile(c,r,id);
        return message("房间修改成功");
    }

    private String roomImpact(Connection c,HttpServletRequest r) throws SQLException {
        var actor=requireAdmin(r);
        long room=longNumber(required(r,"room_id"),1,"房间编号");
        var scoped=com.sws.studyroom.common.Sql.one(c,"SELECT manager_user_id FROM room WHERE room_id=?",room);
        if(scoped==null || actor.role()==SessionIdentity.Role.ROOM_ADMIN
                && !Long.toString(actor.userId()).equals(scoped.get("manager_user_id")))
            throw new Failure(404,"房间不存在或不在管理范围内");
        var now=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        String query="""
                FROM booking WHERE room_id=? AND end_time>?
                AND (booking_status IN ('PENDING_CHECKIN','IN_USE') OR booking_status='PENDING_PAYMENT' AND pay_deadline>?)
                """;
        var sql=com.sws.studyroom.common.Sql.one(c,"SELECT COUNT(*) AS order_count "+query,room,now,now);
        sql.put("orders",com.sws.studyroom.common.Sql.rows(c,"SELECT booking_id,start_time,end_time,booking_status "+query
                +" ORDER BY start_time LIMIT 10",room,now,now));
        return com.sws.studyroom.common.Json.encode(sql);
    }

    private String updateStatus(Connection c, HttpServletRequest r) throws SQLException {
        SessionIdentity identity = requireAdmin(r);
        long id = longNumber(required(r, "room_id"), 1, "房间编号");
        String listing = required(r, "listing_status"), maintenance = required(r, "maintenance_status");
        if (!Set.of("LISTED", "UNLISTED").contains(listing)) throw new IllegalArgumentException("上架状态无效");
        if (!Set.of("NORMAL", "MAINTENANCE").contains(maintenance)) throw new IllegalArgumentException("维护状态无效");
        String scope = identity.role() == SessionIdentity.Role.ROOM_ADMIN ? " AND manager_user_id=?" : "";
        try (PreparedStatement p = c.prepareStatement("UPDATE room SET listing_status=?,maintenance_before_fault=IF(fault_maintenance_active=1,?,maintenance_before_fault),maintenance_status=IF(fault_maintenance_active=1,'MAINTENANCE',?) WHERE room_id=?" + scope)) {
            bind(p, listing, maintenance, maintenance, id); if (!scope.isEmpty()) p.setLong(5, identity.userId());
            if (p.executeUpdate() != 1) throw new Failure(404, "房间不存在或不在管理范围内");
        }
        return message("房间状态更新成功");
    }

    private SessionIdentity requireAdmin(HttpServletRequest r) {
        SessionIdentity id = SessionIdentity.current(r).orElseThrow(() -> new Failure(401, "请先登录"));
        if (id.role() != SessionIdentity.Role.ROOM_ADMIN && id.role() != SessionIdentity.Role.SUPER_ADMIN)
            throw new Failure(403, "需要房管或超级管理员权限");
        return id;
    }

    @Override public Optional<ModuleContracts.RoomView> findRoom(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT listing_status,maintenance_status FROM room WHERE room_id=?")) {
            p.setLong(1, id);
            try (ResultSet row = p.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new ModuleContracts.RoomView(id,
                        ModuleContracts.ListingStatus.valueOf(row.getString(1)),
                        ModuleContracts.MaintenanceStatus.valueOf(row.getString(2))));
            }
        }
    }

    private static String session(HttpServletRequest r) {
        return SessionIdentity.current(r).map(id -> "{\"authenticated\":true,\"user_id\":\"" + id.userId() + "\",\"role\":\"" + id.role() + "\"}")
                .orElse("{\"authenticated\":false}");
    }

    private static String token(HttpServletRequest r) {
        return SessionIdentity.csrf(r);
    }

    private static String confirmedPassword(HttpServletRequest r, String field) {
        String password = required(r, field);
        validatePassword(password);
        if (!same(password, required(r, "password_confirm")))
            throw new IllegalArgumentException("两次输入的密码不一致");
        return password;
    }

    private static void validatePassword(String value) {
        if (value.length() < 8 || value.length() > 72 || value.chars().noneMatch(Character::isLetter) || value.chars().noneMatch(Character::isDigit))
            throw new IllegalArgumentException("密码需为8至72位并同时包含字母和数字");
    }

    private static String hash(String password, String salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(salt), 120_000, 256);
        try { return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
        finally { spec.clearPassword(); }
    }

    private static String randomHex(int size) { byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
    private static boolean same(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String required(HttpServletRequest r, String name) { String v = r.getParameter(name); if (v == null || v.isBlank()) throw new IllegalArgumentException(name + "不能为空"); return v.trim(); }
    private static String limited(String v, int max, String label) { if (v.length() > max) throw new IllegalArgumentException(label + "不能超过" + max + "个字符"); return v; }
    private static int number(String v, int min, int max, String label) { if (v == null || v.isBlank()) return min; try { int n = Integer.parseInt(v); if (n < min || n > max) throw new NumberFormatException(); return n; } catch (NumberFormatException e) { throw new IllegalArgumentException(label + "格式不正确"); } }
    private static long longNumber(String v, long min, String label) { if ((v == null || v.isBlank()) && min == 0) return 0; try { long n = Long.parseLong(v); if (n < min) throw new NumberFormatException(); return n; } catch (NumberFormatException e) { throw new IllegalArgumentException(label + "格式不正确"); } }
    private static Long optionalLong(String v) { return v == null || v.isBlank() ? null : longNumber(v, 1, "房管用户编号"); }
    private static void bind(PreparedStatement p, Object... values) throws SQLException { for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]); }
    private static String message(String v) { return "{\"message\":\"" + json(v) + "\"}"; }
    private static String json(String v) { if (v == null) return ""; return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"); }
    private static void output(HttpServletResponse r, int status, String body) throws IOException { r.setStatus(status); r.getWriter().print(body); }

    private static final class Failure extends RuntimeException {
        private final int status;
        private Failure(int status, String message) { super(message); this.status = status; }
    }
}

