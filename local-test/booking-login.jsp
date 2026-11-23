<%@ page contentType="text/html;charset=UTF-8" import="com.sws.studyroom.common.SessionIdentity,com.sws.studyroom.common.ModuleContracts.CreditAccess,java.sql.*,java.nio.charset.StandardCharsets,java.security.MessageDigest" %>
<%!
private static final class FixtureCredit implements CreditAccess {
    public int findScore(Connection c, long user) throws SQLException {
        try (var p = c.prepareStatement("SELECT credit_score FROM user WHERE user_id=? FOR UPDATE")) {
            p.setLong(1, user);
            try (var r = p.executeQuery()) {
                if (!r.next()) throw new SQLException("Unknown fixture user");
                return r.getInt(1);
            }
        }
    }
    public boolean applyChange(Connection c, long user, String event, int delta, String reason) throws SQLException {
        int score = Math.max(0, Math.min(300, findScore(c, user) + delta));
        try (var p = c.prepareStatement("INSERT INTO booking_fixture_credit(event_id,user_id) VALUES (?,?)")) {
            p.setString(1, event);
            p.setLong(2, user);
            try { p.executeUpdate(); }
            catch (SQLException e) { if (e.getErrorCode() == 1062) return false; throw e; }
        }
        try (var p = c.prepareStatement("UPDATE user SET credit_score=? WHERE user_id=?")) {
            p.setInt(1, score);
            p.setLong(2, user);
            p.executeUpdate();
        }
        return true;
    }
}
%>
<%
String key = System.getenv("SWS_FIXTURE_KEY");
if (key == null || key.length() < 24 ||
    !java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
    response.sendError(404);
    return;
}
response.setHeader("Cache-Control", "no-store");
if ("POST".equals(request.getMethod())) {
    String given = request.getParameter("key");
    if (given == null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
        response.sendError(403);
        return;
    }
    String account = request.getParameter("account");
    if (!"9001".equals(account) && !"9002".equals(account) && !"9003".equals(account)) {
        response.sendError(400);
        return;
    }
    SessionIdentity.Role role = "9003".equals(account) ? SessionIdentity.Role.SUPER_ADMIN : SessionIdentity.Role.USER;
    SessionIdentity.signIn(request, new SessionIdentity(Long.parseLong(account), role));
    synchronized (application) {
        if (application.getAttribute(CreditAccess.class.getName()) == null)
            application.setAttribute(CreditAccess.class.getName(), new FixtureCredit());
    }
    response.sendRedirect(request.getContextPath() + "/booking-rules/");
    return;
}
%>
<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>预约本地测试</title>
<h1>预约本地测试</h1>
<form method="post">
<label>测试密钥 <input name="key" type="password" required></label>
<label>测试账号 <select name="account"><option>9001</option><option>9002</option><option>9003</option></select></label>
<button>进入预约页面</button>
</form></html>