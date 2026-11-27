package com.sws.studyroom.usagefeedback;

import com.sws.studyroom.common.SessionIdentity;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;

/**
 * 签到释放、信用违规、评价、故障、申诉 控制层
 * 对应设计文档 6.3.5~6.3.9 UsageController / CreditController / ReviewController / FaultController / AppealController
 *
 */
@WebServlet("/api/usage-feedback/*")
public class UsageFeedbackServlet extends HttpServlet {

    // 生产环境从环境变量读取 SWS_DB_URL / SWS_DB_USER / SWS_DB_PASSWORD
    private Connection getDbConnection() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String url = System.getenv("SWS_DB_URL");
        String user = System.getenv("SWS_DB_USER");
        String password = System.getenv("SWS_DB_PASSWORD");
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var currOpt = SessionIdentity.current(req);
        if (currOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Long loginUserId = currOpt.get().userId();

        resp.setContentType("application/json;charset=UTF-8");
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isBlank()) {
            pathInfo = "/";
        }

        if ("/me/credit".equals(pathInfo)) {
            resp.getWriter().write("{\"status\":\"ok\",\"user_id\":" + loginUserId + ",\"note\":\"此处调用信用服务查询流水\"}");
            return;
        }
        resp.getWriter().write("{\"status\":\"ok\",\"module\":\"usage_feedback\"}");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        var currOpt = SessionIdentity.current(req);
        if (currOpt.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Long loginUserId = currOpt.get().userId();

        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.isBlank()) {
            pathInfo = "/";
        }


        String bodyJson = readBody(req);
        resp.setContentType("application/json;charset=UTF-8");

        switch (pathInfo) {
            case "/checkin":
                handleCheckIn(req, resp, loginUserId, bodyJson);
                break;
            case "/door/retry":
                handleRetryDoor(req, resp, loginUserId, bodyJson);
                break;
            case "/finish":
                handleFinishEarly(req, resp, loginUserId, bodyJson);
                break;
            case "/fault":
                handleReportFault(req, resp, loginUserId, bodyJson);
                break;
            case "/appeal":
                handleSubmitAppeal(req, resp, loginUserId, bodyJson);
                break;
            case "/review":
                handleSubmitReview(req, resp, loginUserId, bodyJson);
                break;
            default:
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("{\"error\":\"unknown_api\"}");
                break;
        }
    }

    /**
     * 简易 JSON 字段提取，无第三方依赖，适配原版 pom
     */
    private String extractJsonStr(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int start = idx + search.length();
        char c = json.charAt(start);
        if (c == '"') {
            int end = json.indexOf('"', start + 1);
            if (end == -1) return null;
            return json.substring(start + 1, end);
        } else {
            int end1 = json.indexOf(',', start);
            int end2 = json.indexOf('}', start);
            int end = (end1 == -1) ? end2 : Math.min(end1, end2);
            return json.substring(start, end).trim();
        }
    }

    /** 签到开门：TODO 待补数据库逻辑（校验订单归属、时间窗、状态流转、写 usage 表、信用加分幂等） */
    private void handleCheckIn(HttpServletRequest req, HttpServletResponse resp, Long loginUserId, String json) throws IOException {
        String bookingIdStr = extractJsonStr(json, "booking_id");
        String eventNo = extractJsonStr(json, "event_no");
        if (bookingIdStr == null || eventNo == null || bookingIdStr.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"参数缺失：booking_id、event_no不能为空\"}");
            return;
        }
        Long bookingId;
        try {
            bookingId = Long.parseLong(bookingIdStr);
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"booking_id格式错误\"}");
            return;
        }
        resp.getWriter().write(String.format("""
                {
                    "code":200,
                    "msg":"签到功能待开发，暂未写入数据库",
                    "data":{"booking_id":%d,"event_no":"%s"}
                }
                """, bookingId, eventNo));
    }

    /** 门禁重试：TODO 仅重试门禁，不修改订单、不修改信用 */
    private void handleRetryDoor(HttpServletRequest req, HttpServletResponse resp, Long loginUserId, String json) throws IOException {
        String bookingIdStr = extractJsonStr(json, "booking_id");
        if (bookingIdStr == null || bookingIdStr.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"参数缺失：booking_id不能为空\"}");
            return;
        }
        Long bookingId;
        try {
            bookingId = Long.parseLong(bookingIdStr);
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"booking_id格式错误\"}");
            return;
        }
        resp.getWriter().write(String.format("""
                {
                    "code":200,
                    "msg":"门禁重试功能待开发",
                    "data":{"booking_id":%d}
                }
                """, bookingId));
    }

    /** 提前结束：更新 `usage` 表 actual_end_time */
    private void handleFinishEarly(HttpServletRequest req, HttpServletResponse resp, Long loginUserId, String json) throws IOException {
        String bookingIdStr = extractJsonStr(json, "booking_id");
        if (bookingIdStr == null || bookingIdStr.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"参数缺失：booking_id不能为空\"}");
            return;
        }
        Long bookingId;
        try {
            bookingId = Long.parseLong(bookingIdStr);
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"booking_id格式错误\"}");
            return;
        }

        // ============ 这里修改，usage加上反引号 ============
        String sql = "UPDATE `usage` SET actual_end_time = ? WHERE booking_id = ?";
        try (Connection conn = getDbConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
            ps.setLong(2, bookingId);
            int affect = ps.executeUpdate();
            if (affect <= 0) {
                resp.getWriter().write("{\"code\":400,\"msg\":\"未找到该预约签到记录，无法提前结束\"}");
                return;
            }
            resp.getWriter().write(String.format("""
                    {
                        "code":200,
                        "msg":"研讨间提前结束使用成功",
                        "data":{"booking_id":%d}
                    }
                    """, bookingId));
        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"code\":500,\"msg\":\"数据库更新异常：" + e.getMessage().replace("\"", "") + "\"}");
        }
    }

    /** 上报故障：写入 fault 表 */
    private void handleReportFault(HttpServletRequest req, HttpServletResponse resp, long userId, String bodyJson) throws IOException {
        String roomIdStr = extractJsonStr(bodyJson, "room_id");
        String faultDesc = extractJsonStr(bodyJson, "fault_desc");
        if (roomIdStr == null || faultDesc == null || faultDesc.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"参数缺失：room_id、故障描述不能为空\"}");
            return;
        }
        Long roomId;
        try {
            roomId = Long.parseLong(roomIdStr);
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"room_id格式错误\"}");
            return;
        }

        String sql = "INSERT INTO fault(room_id, user_id, fault_desc, fault_status, create_time) VALUES (?,?,?,?,?)";
        try (Connection conn = getDbConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, roomId);
            ps.setLong(2, userId);
            ps.setString(3, faultDesc);
            ps.setString(4, "待受理");
            ps.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();

            resp.getWriter().write(String.format("""
                    {
                        "code":200,
                        "msg":"故障上报成功",
                        "data":{"room_id":%d,"fault_desc":"%s","fault_status":"待受理"}
                    }
                    """, roomId, faultDesc.replace("\"", "\\\"")));
        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"code\":500,\"msg\":\"数据库写入异常：" + e.getMessage().replace("\"", "") + "\"}");
        }
    }

    /** 提交申诉：TODO 待补数据库逻辑（校验违规归属、同违规不重复申诉、写 appeal 表） */
    private void handleSubmitAppeal(HttpServletRequest req, HttpServletResponse resp, Long loginUserId, String json) throws IOException {
        String bookingIdStr = extractJsonStr(json, "booking_id");
        String vioIdStr = extractJsonStr(json, "violation_id");
        String reason = extractJsonStr(json, "appeal_reason");
        if (bookingIdStr == null || vioIdStr == null || reason == null || reason.isBlank()) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"参数缺失：booking_id、violation_id、申诉理由不能为空\"}");
            return;
        }
        Long bookingId, violationId;
        try {
            bookingId = Long.parseLong(bookingIdStr);
            violationId = Long.parseLong(vioIdStr);
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"id参数格式错误\"}");
            return;
        }
        resp.getWriter().write(String.format("""
                {
                    "code":200,
                    "msg":"申诉功能待开发，暂未写入数据库",
                    "data":{"booking_id":%d,"violation_id":%d,"appeal_reason":"%s"}
                }
                """, bookingId, violationId, reason.replace("\"", "\\\"")));
    }

    /** 提交评价：先查重（一单只能评一次），通过后写入 review 表 */
    private void handleSubmitReview(HttpServletRequest req, HttpServletResponse resp, Long loginUserId, String json) throws IOException {
        String bookingIdStr = extractJsonStr(json, "booking_id");
        String s1Str = extractJsonStr(json, "score_1");
        String s2Str = extractJsonStr(json, "score_2");
        String comment = extractJsonStr(json, "comment");

        if (bookingIdStr == null || s1Str == null || s2Str == null) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"参数缺失：booking_id、score_1、score_2不能为空\"}");
            return;
        }
        Long bookingId;
        int score1, score2;
        try {
            bookingId = Long.parseLong(bookingIdStr);
            score1 = Integer.parseInt(s1Str);
            score2 = Integer.parseInt(s2Str);
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"参数数字格式错误\"}");
            return;
        }
        if (score1 < 1 || score1 > 5 || score2 < 1 || score2 > 5) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resp.getWriter().write("{\"code\":400,\"msg\":\"分数必须是1~5之间\"}");
            return;
        }
        if (comment == null) comment = "";

        try (Connection conn = getDbConnection();
             PreparedStatement checkPs = conn.prepareStatement("SELECT review_id FROM review WHERE booking_id = ?")) {
            checkPs.setLong(1, bookingId);
            var rs = checkPs.executeQuery();
            if (rs.next()) {
                resp.getWriter().write("{\"code\":400,\"msg\":\"该预约已经提交过评价，不可重复提交\"}");
                return;
            }

            String insertSql = "INSERT INTO review(booking_id, user_id, score_1, score_2, comment, create_time) VALUES (?,?,?,?,?,?)";
            try (PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                insertPs.setLong(1, bookingId);
                insertPs.setLong(2, loginUserId);
                insertPs.setInt(3, score1);
                insertPs.setInt(4, score2);
                insertPs.setString(5, comment);
                insertPs.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
                insertPs.executeUpdate();
            }
        } catch (Exception e) {
            e.printStackTrace();
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.getWriter().write("{\"code\":500,\"msg\":\"数据库异常：" + e.getMessage().replace("\"", "") + "\"}");
            return;
        }

        resp.getWriter().write(String.format("""
                {
                    "code":200,
                    "msg":"评价提交成功",
                    "data":{"booking_id":%d,"score_1":%d,"score_2":%d,"comment":"%s"}
                }
                """, bookingId, score1, score2, comment.replace("\"", "\\\"")));
    }

    private String readBody(HttpServletRequest req) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = req.getReader()) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
