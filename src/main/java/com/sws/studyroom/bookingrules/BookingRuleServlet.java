package com.sws.studyroom.bookingrules;

import com.sws.studyroom.common.Database;
import com.sws.studyroom.common.ModuleContracts.*;
import com.sws.studyroom.common.SessionIdentity;
import com.sws.studyroom.common.Sql;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.Optional;

@WebServlet(value = "/api/booking-rules/*", loadOnStartup = 1)
public class BookingRuleServlet extends HttpServlet implements BookingAccess {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Override
    public void init() {
        getServletContext().setAttribute(BookingAccess.class.getName(), this);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        serve(req, res, false);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        serve(req, res, true);
    }

    private void serve(HttpServletRequest req, HttpServletResponse res, boolean write) throws IOException {
        req.setCharacterEncoding("UTF-8");
        res.setContentType("text/plain;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        var identity = SessionIdentity.current(req).orElse(null);
        if (identity == null) {
            res.setStatus(401);
            res.getWriter().println("请先登录");
            return;
        }
        String path = req.getPathInfo();
        if ("/order-data".equals(path) || "/rule-data".equals(path) || "/admin/refunds".equals(path) || "/preview".equals(path)) res.setContentType("application/json;charset=UTF-8");
        boolean admin = path != null && path.startsWith("/admin/");
        if (admin && identity.role() != SessionIdentity.Role.SUPER_ADMIN) {
            res.setStatus(403);
            res.getWriter().println("需要超级管理员权限");
            return;
        }
        if (write) {
            if (!SessionIdentity.validCsrf(req, req.getParameter("csrf"))) {
                res.setStatus(403);
                res.getWriter().println("请刷新预约页面后重试");
                return;
            }
        } else if ("/token".equals(path)) {
            var session = req.getSession();
            synchronized (session) {
                if (session.getAttribute("bookingFormToken") == null) {
                    session.setAttribute("bookingFormToken", java.util.UUID.randomUUID().toString());
                }
                res.getWriter().print(SessionIdentity.csrf(req));
            }
            return;
        }

        try (Connection c = Database.open()) {
            c.setAutoCommit(false);
            try {
                if (("/orders".equals(path) || "/order-data".equals(path)) && !write) expirePending(c, identity.userId());
                String output = write ? change(c, req, identity.userId(), path)
                                      : read(c, req, identity.userId(), path);
                c.commit();
                res.getWriter().print(output);
            } catch (SQLException | RuntimeException ex) {
                c.rollback();
                throw ex;
            }
        } catch (IllegalArgumentException | DateTimeException ex) {
            res.setStatus(400);
            res.getWriter().println(ex instanceof NumberFormatException || ex instanceof DateTimeException
                    ? "编号、金额或时间格式有误" : ex.getMessage());
        } catch (SQLException ex) {
            if (ex.getErrorCode() == 1062) {
                res.setStatus(409);
                res.getWriter().println("时段已被预约，或操作已经处理");
            } else {
                log("预约模块数据库操作失败", ex);
                res.setStatus(500);
                res.getWriter().println("数据库操作失败");
            }
        }
    }

    private String read(Connection c, HttpServletRequest r, long user, String path) throws SQLException {
        return switch (path == null ? "" : path) {
            case "/rules", "/admin/rules" -> {
                Rule rule = rule(c, null);
                yield "可提前预约 " + rule.days() + " 天，每日最多 " + rule.slots()
                        + " 个时段；每小时 " + rule.price() + " 元";
            }
            case "/orders" -> orders(c, user);
            case "/order-data" -> com.sws.studyroom.common.Json.encode(com.sws.studyroom.common.Sql.rows(c,
                    "SELECT b.*,r.room_name,p.pay_amount,f.refund_amount,u.actual_end_time,u.door_open_result,"
                    + "o.release_reason,o.released_at,rr.status AS refund_state,rr.reason AS refund_reason,rr.request_no,"
                    + "TIMESTAMPADD(HOUR,rv.review_window_hours,u.actual_end_time) AS review_deadline,"
                    + "CASE WHEN v.booking_id IS NOT NULL THEN 'SUBMITTED' "
                    + "WHEN b.booking_status='FINISHED' AND ? BETWEEN u.actual_end_time AND "
                    + "TIMESTAMPADD(HOUR,rv.review_window_hours,u.actual_end_time) THEN 'OPEN' ELSE 'CLOSED' END AS review_state "
                    + "FROM booking b JOIN room r USING(room_id) JOIN rule_version rv USING(rule_version_id) "
                    + "LEFT JOIN payment p USING(booking_id) LEFT JOIN refund f USING(booking_id) "
                    + "LEFT JOIN `usage` u USING(booking_id) LEFT JOIN review v USING(booking_id) "
                    + "LEFT JOIN booking_outcome o USING(booking_id) LEFT JOIN simulated_refund_request rr USING(booking_id) "
                    + "WHERE b.user_id=? ORDER BY b.booking_id DESC LIMIT 100", now(), user));
            case "/admin/refunds" -> com.sws.studyroom.common.Json.encode(Sql.rows(c, """
                    SELECT rr.booking_id,rr.request_no,rr.amount,rr.status,rr.reason,rr.requested_at,r.room_name
                    FROM simulated_refund_request rr JOIN booking b USING(booking_id) JOIN room r USING(room_id)
                    WHERE rr.status IN ('FAILED','PROCESSING')
                    ORDER BY rr.requested_at,rr.booking_id LIMIT 100
                    """));
            case "/rule-data" -> com.sws.studyroom.common.Json.encode(ruleDetails(c, false));
            case "/preview" -> com.sws.studyroom.common.Json.encode(preview(c,r,user,now()));
            case "/quote" -> {
                BookingView order = owned(c, number(r, "booking_id"), user);
                require(order.bookingStatus() == BookingStatus.PENDING_CHECKIN, "订单当前不可退款");
                yield "预计退款 " + refundAmount(c, order, now()) + " 元";
            }
            case "/admin/credit" -> credit(c, number(r, "user_id"));
            default -> throw new IllegalArgumentException("未知操作");
        };
    }

    private String change(Connection c, HttpServletRequest r, long user, String path) throws SQLException {
        LocalDateTime now = now();
        return switch (path == null ? "" : path) {
            case "/orders" -> create(c, r, user, now);
            case "/pay", "/cancel", "/refund" -> {
                BookingView initial = owned(c, number(r, "booking_id"), user);
                try (var p=query(c,"SELECT user_id FROM `user` WHERE user_id=? FOR UPDATE",user);var rows=p.executeQuery()) { rows.next(); }
                try (var p=query(c,"SELECT room_id FROM room WHERE room_id=? FOR UPDATE",initial.roomId());var rows=p.executeQuery()) { rows.next(); }
                try (var p=query(c,"SELECT booking_id FROM booking WHERE booking_id=? FOR UPDATE",initial.bookingId());var rows=p.executeQuery()) { rows.next(); }
                BookingView order=owned(c,initial.bookingId(),user);
                yield switch (path) {
                    case "/pay" -> pay(c, order, now, simulationOutcome(r, false));
                    case "/cancel" -> {
                        require(release(c, order.bookingId(), BookingStatus.PENDING_PAYMENT,
                                ReleaseReason.UNPAID_CANCELLED, ""), "订单已处理");
                        yield "已取消，时段已释放";
                    }
                    default -> refund(c, order, now, simulationOutcome(r, true));
                };
            }
            case "/admin/resolve-refund" -> resolveRefund(c,user,number(r,"booking_id"),now);
            case "/admin/rules" -> {
                int days = integer(r, "advance_days", 1, 7);
                int slots = integer(r, "daily_slots", 1, 24);
                int percent = integer(r, "refund_percent", 0, 100);
                BigDecimal price = new BigDecimal(parameter(r, "hourly_price")).setScale(2, java.math.RoundingMode.HALF_UP);
                require(price.signum() >= 0 && price.compareTo(new BigDecimal("10000")) <= 0, "价格不合法");
                var current = ruleDetails(c, true);
                int farPercent = optionalInteger(r,current,"refund_early_percent",0,100);
                require(farPercent >= percent,"提前退款比例不能低于临近退款比例");
                if (r.getParameter("rule_version_id") != null)
                    require(number(r,"rule_version_id") == Long.parseLong(current.get("rule_version_id").toString()), "规则已更新，请刷新后重试");
                long version = Sql.insert(c, "INSERT INTO rule_version(advance_days,daily_slots,hourly_price,refund_percent,checkin_early_minutes,checkin_late_minutes,timeout_deduct,early_release_deduct,credit_suspend_days,review_window_hours) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        days, slots, price, percent,
                        optionalInteger(r,current,"checkin_early_minutes",0,60), optionalInteger(r,current,"checkin_late_minutes",0,60),
                        optionalInteger(r,current,"timeout_deduct",0,300),optionalInteger(r,current,"early_release_deduct",0,300),
                        optionalInteger(r,current,"credit_suspend_days",3,7),optionalInteger(r,current,"review_window_hours",1,168));
                Sql.update(c, "INSERT INTO rule_details(rule_version_id,max_booking_hours,refund_cutoff_hours,refund_early_percent,fulfillment_bonus,review_bonus,overstay_deduct,untidy_deduct) VALUES (?,?,?,?,?,?,?,?)",version,
                        optionalInteger(r,current,"max_booking_hours",1,24),
                        optionalInteger(r,current,"refund_cutoff_hours",1,168),farPercent,
                        optionalInteger(r,current,"fulfillment_bonus",0,300),optionalInteger(r,current,"review_bonus",0,300),
                        optionalInteger(r,current,"overstay_deduct",0,300),optionalInteger(r,current,"untidy_deduct",0,300));
                audit(c,user,"RULE_PUBLISHED",version,null,now);
                yield "规则已保存，新订单使用新规则";
            }
            case "/admin/freeze" -> {
                String action = parameter(r, "action");
                require(action.equals("freeze") || action.equals("unfreeze"), "无效操作");
                String target = action.equals("freeze") ? "DISABLED" : "ACTIVE";
                String source = action.equals("freeze") ? "ACTIVE" : "DISABLED";
                require(update(c, "UPDATE user SET account_status=? WHERE user_id=? AND account_status=?",
                        target, number(r, "user_id"), source) == 1, "用户不存在或状态不匹配");
                audit(c,user,action.equals("freeze")?"ACCOUNT_FROZEN":"ACCOUNT_UNFROZEN",number(r,"user_id"),null,now);
                yield "用户状态已更新";
            }
            case "/admin/credit" -> {
                Object service = getServletContext().getAttribute(CreditAccess.class.getName());
                if (!(service instanceof CreditAccess credit)) {
                    throw new IllegalArgumentException("信用记账服务尚未接入");
                }
                String event = parameter(r, "event_id");
                require(event.length() <= 60, "事件编号过长");
                boolean changed = credit.applyChange(c, number(r, "user_id"), "admin:" + event,
                        integer(r, "delta", -300, 300), parameter(r, "reason"));
                if (changed) audit(c,user,"CREDIT_ADJUSTED",number(r,"user_id"),parameter(r,"reason"),now);
                yield changed ? "信用分已调整" : "该事件已处理";
            }
            default -> throw new IllegalArgumentException("未知操作");
        };
    }

    private Object preview(Connection c,HttpServletRequest r,long user,LocalDateTime now) throws SQLException {
        long room = number(r,"room_id");
        var date = LocalDate.parse(parameter(r,"date"));
        Rule rule = rule(c,null);
        require(!date.isBefore(now.toLocalDate()) && !date.isAfter(now.toLocalDate().plusDays(rule.days())), "日期超出预约范围");
        var resource = Sql.one(c,"SELECT room_id FROM room WHERE room_id=? AND listing_status='LISTED' AND maintenance_status='NORMAL'",room);
        require(resource!=null,"房间当前不可预约");
        var occupied = Sql.rows(c,"""
                SELECT s.slot_start,s.room_id,s.user_id
                FROM slot_lock s JOIN booking b USING(booking_id)
                WHERE s.slot_start>=? AND s.slot_start<? AND (s.room_id=? OR s.user_id=?)
                    AND (b.booking_status<>'PENDING_PAYMENT' OR b.pay_deadline>?)
                ORDER BY s.slot_start
                """,date.atStartOfDay(),date.plusDays(1).atStartOfDay(),room,user,now);
        var blocked = new java.util.LinkedHashSet<String>();
        int used = 0;
        for (var slot : occupied) {
            blocked.add(slot.get("slot_start").toString().substring(11,16));
            if (Long.parseLong(slot.get("user_id").toString())==user) used++;
        }
        return java.util.Map.of("blocked_hours",blocked,"remaining_hours",Math.max(0,rule.slots()-used),"max_booking_hours",rule.maxHours());
    }

    private String create(Connection c, HttpServletRequest r, long user, LocalDateTime now) throws SQLException {
        long room = number(r, "room_id");
        LocalDateTime start = LocalDateTime.parse(parameter(r, "start_time"));
        LocalDateTime end = LocalDateTime.parse(parameter(r, "end_time"));
        Rule rule = rule(c, null);
        long slots = Duration.between(start, end).toHours();
        require(start.isAfter(now) && !start.toLocalDate().isAfter(now.toLocalDate().plusDays(rule.days()))
                && start.toLocalDate().equals(end.minusNanos(1).toLocalDate())
                && start.equals(start.truncatedTo(java.time.temporal.ChronoUnit.HOURS))
                && end.equals(end.truncatedTo(java.time.temporal.ChronoUnit.HOURS))
                && slots > 0 && slots <= Math.min(rule.slots(),rule.maxHours()), "请选择规则范围内的整点时段");
        lockEligibleUser(c, user, now);
        expirePending(c, user);
        try (var p = query(c, "SELECT listing_status, maintenance_status, opening_hours FROM room WHERE room_id=? FOR UPDATE", room);
             var rows = p.executeQuery()) {
            require(rows.next() && "LISTED".equals(rows.getString(1))
                    && "NORMAL".equals(rows.getString(2)), "房间当前不可预约");
            String[] hours = rows.getString(3).split("-", -1);
            require(hours.length == 2 && !start.toLocalTime().isBefore(LocalTime.parse(hours[0].trim()))
                    && end.toLocalDate().equals(start.toLocalDate())
                    && !end.toLocalTime().isAfter(LocalTime.parse(hours[1].trim())), "时段超出房间开放时间");
        }
        try (var p = query(c, "SELECT COUNT(*) FROM slot_lock WHERE user_id=? AND slot_start>=? AND slot_start<?",
                user, start.toLocalDate().atStartOfDay(), start.toLocalDate().plusDays(1).atStartOfDay());
             var rows = p.executeQuery()) {
            rows.next();
            require(rows.getLong(1) + slots <= rule.slots(), "超过单日预约上限");
        }
        long id;
        try (var p = c.prepareStatement("INSERT INTO booking(user_id,room_id,start_time,end_time,total_amount,booking_status,rule_version_id,pay_deadline) VALUES (?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            bind(p, user, room, start, end, rule.price().multiply(BigDecimal.valueOf(slots)),
                    BookingStatus.PENDING_PAYMENT.name(), rule.id(), start.isBefore(now.plusMinutes(15)) ? start : now.plusMinutes(15));
            p.executeUpdate();
            try (var keys = p.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("缺少订单编号");
                id = keys.getLong(1);
            }
        }
        for (LocalDateTime slot = start; slot.isBefore(end); slot = slot.plusHours(1)) {
            update(c, "INSERT INTO slot_lock(booking_id,user_id,room_id,slot_start,slot_end) VALUES (?,?,?,?,?)",
                    id, user, room, slot, slot.plusHours(1));
        }
        com.sws.studyroom.common.Messages.send(c,user,"booking:"+id,"BOOKING_CREATED",
                "预约订单已生成","订单 #"+id+" 已锁定时段，请在付款截止时间前完成支付。",now);
        return "已生成待支付订单 " + id;
    }

    private void lockEligibleUser(Connection c, long user, LocalDateTime now) throws SQLException {
        try (var p = query(c, "SELECT account_status,credit_suspended_until FROM user WHERE user_id=? FOR UPDATE", user);
             var row = p.executeQuery()) {
            require(row.next(), "用户不存在");
            String status = row.getString(1);
            Timestamp suspended = row.getTimestamp(2);
            require("ACTIVE".equals(status)
                    && (suspended == null || !suspended.toLocalDateTime().isAfter(now)), "当前账号无法预约");
        }
    }

    private void expirePending(Connection c, long user) throws SQLException {
        var expired = new java.util.ArrayList<Long>();
        try (var p = query(c, "SELECT booking_id FROM booking WHERE user_id=? AND booking_status=? AND pay_deadline<=?",
                user, BookingStatus.PENDING_PAYMENT.name(), now()); var rows = p.executeQuery()) {
            while (rows.next()) expired.add(rows.getLong(1));
        }
        for (long id : expired) {
            release(c, id, BookingStatus.PENDING_PAYMENT, ReleaseReason.PAYMENT_TIMEOUT, "");
        }
    }

    private static String simulationOutcome(HttpServletRequest request, boolean refund) {
        String result = request.getParameter("simulation_outcome");
        if (result == null || result.isBlank()) return "SUCCESS";
        require(result.equals("SUCCESS") || result.equals("FAILED") || (refund && result.equals("PROCESSING")) || (!refund && result.equals("LATE_SUCCESS")),
                "无效的模拟结果");
        return result;
    }

    private String pay(Connection c, BookingView order, LocalDateTime now, String result) throws SQLException {
        if ("LATE_SUCCESS".equals(result)) return compensateLatePayment(c,order,now);
        require(order.bookingStatus() == BookingStatus.PENDING_PAYMENT, "订单当前不可支付");
        try (var p = query(c, "SELECT pay_deadline FROM booking WHERE booking_id=?", order.bookingId());
             var row = p.executeQuery()) {
            row.next();
            if (!now.isBefore(row.getObject(1, LocalDateTime.class))) {
                release(c, order.bookingId(), BookingStatus.PENDING_PAYMENT, ReleaseReason.PAYMENT_TIMEOUT, "");
                return "支付已超时，订单已取消";
            }
        }
        if ("FAILED".equals(result)) {
            require(release(c, order.bookingId(), BookingStatus.PENDING_PAYMENT, ReleaseReason.PAYMENT_FAILED, ""),
                    "订单已处理");
            return "模拟支付失败，订单已取消，时段已释放";
        }
        require(changeStatus(c, order.bookingId(), BookingStatus.PENDING_PAYMENT, BookingStatus.PENDING_CHECKIN),
                "订单已处理");
        update(c, "INSERT INTO payment(booking_id,pay_amount,paid_at) SELECT booking_id,total_amount,? FROM booking WHERE booking_id=?",
                now, order.bookingId());
        return "模拟支付成功，等待签到";
    }

    private String compensateLatePayment(Connection c,BookingView order,LocalDateTime now) throws SQLException {
        long id = order.bookingId();
        if (order.bookingStatus()==BookingStatus.PENDING_PAYMENT) {
            var deadline=Sql.one(c,"SELECT pay_deadline FROM booking WHERE booking_id=?",id);
            require(!now.isBefore(LocalDateTime.parse(deadline.get("pay_deadline").toString().replace(' ','T'))),"支付仍在有效期内");
            require(release(c,id,BookingStatus.PENDING_PAYMENT,ReleaseReason.PAYMENT_TIMEOUT,""),"订单状态已变化");
        } else {
            require(order.bookingStatus()==BookingStatus.CANCELLED,"仅取消的未支付订单可做迟到支付补偿");
            var outcome=Sql.one(c,"SELECT release_reason FROM booking_outcome WHERE booking_id=?",id);
            require(outcome!=null && java.util.Set.of("PAYMENT_FAILED","PAYMENT_TIMEOUT","UNPAID_CANCELLED")
                    .contains(outcome.get("release_reason")),"订单没有可补偿的支付取消记录");
        }
        if (Sql.one(c,"SELECT booking_id FROM payment WHERE booking_id=?",id)!=null) {
            require(Sql.one(c,"SELECT booking_id FROM simulated_refund_request WHERE booking_id=? AND request_no=?",id,"SIM-LATE-"+id)!=null,
                    "支付已存在");
            return "迟到支付已补偿退款";
        }
        var amount=Sql.one(c,"SELECT total_amount FROM booking WHERE booking_id=?",id).get("total_amount");
        update(c,"INSERT INTO payment(booking_id,pay_amount,paid_at) VALUES (?,?,?)",id,amount,now);
        update(c,"INSERT INTO refund(booking_id,refund_amount,refunded_at) VALUES (?,?,?)",id,amount,now);
        Sql.update(c,"""
                INSERT INTO simulated_refund_request(booking_id,request_no,amount,status,reason,requested_at,updated_at)
                VALUES (?,?,?,'SUCCEEDED','迟到支付全额补偿',?,?)
                """,id,"SIM-LATE-"+id,amount,now,now);
        com.sws.studyroom.common.Messages.send(c,order.userId(),"booking:"+id,"LATE_PAYMENT_REFUNDED",
                "迟到支付已退款","订单 #"+id+" 的模拟付款在取消后到账，已全额退回。",now);
        return "迟到支付已全额补偿退款，预约保持取消";
    }

    private BigDecimal refundAmount(Connection c, BookingView order, LocalDateTime now) throws SQLException {
        require(now.isBefore(order.startTime()), "预约已开始，无法退款");
        Rule rule = rule(c, order.ruleVersionId());
        int percent = now.plusHours(rule.refundCutoff()).isAfter(order.startTime()) ? rule.refundPercent() : rule.earlyRefundPercent();
        try (var p = query(c, "SELECT pay_amount FROM payment WHERE booking_id=?", order.bookingId());
             var rows = p.executeQuery()) {
            require(rows.next(), "未找到付款记录");
            return rows.getBigDecimal(1).multiply(BigDecimal.valueOf(percent)).movePointLeft(2)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }

    private String refund(Connection c, BookingView order, LocalDateTime now, String result) throws SQLException {
        require(order.bookingStatus() == BookingStatus.PENDING_CHECKIN, "订单当前不可退款");
        BigDecimal amount = refundAmount(c, order, now);
        require(changeStatus(c, order.bookingId(), BookingStatus.PENDING_CHECKIN, BookingStatus.CANCELLED), "订单已处理");
        Sql.update(c, """
                INSERT INTO simulated_refund_request(booking_id,request_no,amount,status,reason,requested_at,updated_at)
                VALUES (?,?,?,'PROCESSING',NULL,?,?)
                """, order.bookingId(), "SIM-REFUND-"+order.bookingId(), amount, now, now);
        if ("FAILED".equals(result)) {
            Sql.update(c,"UPDATE simulated_refund_request SET status='FAILED',reason=?,updated_at=? WHERE booking_id=?",
                    "模拟渠道退款失败，等待管理员处理",now,order.bookingId());
            return "退款失败，已转人工处理，时段仍保留";
        }
        if ("PROCESSING".equals(result)) return "退款处理中，时段仍保留";
        return completeRefund(c, order.bookingId(), amount, now);
    }

    private String resolveRefund(Connection c, long actor,long bookingId, LocalDateTime now) throws SQLException {
        var booking = findBooking(c,bookingId).orElseThrow(()->new IllegalArgumentException("找不到订单"));
        Sql.one(c,"SELECT user_id FROM `user` WHERE user_id=? FOR UPDATE",booking.userId());
        Sql.one(c,"SELECT room_id FROM room WHERE room_id=? FOR UPDATE",booking.roomId());
        Sql.one(c,"SELECT booking_id FROM booking WHERE booking_id=? FOR UPDATE",bookingId);
        var request = Sql.one(c,"SELECT amount,status FROM simulated_refund_request WHERE booking_id=? FOR UPDATE",bookingId);
        require(request!=null,"无此退款申请");
        if ("SUCCEEDED".equals(request.get("status"))) return "退款已完成";
        require(findBooking(c,bookingId).orElseThrow().bookingStatus()==BookingStatus.CANCELLED,"订单状态已变化");
        Sql.update(c,"UPDATE simulated_refund_request SET attempts=attempts+1 WHERE booking_id=?",bookingId);
        String result = completeRefund(c, bookingId, (BigDecimal)request.get("amount"), now);
        audit(c,actor,"REFUND_RESOLVED",bookingId,null,now);
        com.sws.studyroom.common.Messages.send(c,booking.userId(),"booking:"+bookingId,"REFUND_RESOLVED",
                "退款已完成","订单 #"+bookingId+" 的模拟退款已完成，房间时段已释放。",now);
        return result;
    }

    private String completeRefund(Connection c,long bookingId,BigDecimal amount,LocalDateTime now) throws SQLException {
        update(c,"INSERT INTO refund(booking_id,refund_amount,refunded_at) VALUES (?,?,?)",bookingId,amount,now);
        release(c,bookingId,BookingStatus.CANCELLED,ReleaseReason.REFUND_SUCCEEDED,"");
        Sql.update(c,"UPDATE simulated_refund_request SET status='SUCCEEDED',reason=NULL,updated_at=? WHERE booking_id=?",now,bookingId);
        return "模拟退款成功，退回 " + amount + " 元";
    }

    @Override
    public Optional<BookingView> findBooking(Connection c, long id) throws SQLException {
        try (var p = query(c, "SELECT user_id,room_id,start_time,end_time,booking_status,rule_version_id FROM booking WHERE booking_id=?", id);
             var row = p.executeQuery()) {
            if (!row.next()) return Optional.empty();
            return Optional.of(new BookingView(id, row.getLong(1), row.getLong(2),
                    row.getObject(3, LocalDateTime.class), row.getObject(4, LocalDateTime.class),
                    BookingStatus.valueOf(row.getString(5)), row.getLong(6)));
        }
    }

    @Override
    public boolean beginUsage(Connection c, long id, BookingStatus expected) throws SQLException {
        return expected == BookingStatus.PENDING_CHECKIN && changeStatus(c, id, expected, BookingStatus.IN_USE);
    }

    @Override
    public boolean release(Connection c, long id, BookingStatus expected, ReleaseReason reason, String eventId)
            throws SQLException {
        BookingStatus allowed = switch (reason) {
            case UNPAID_CANCELLED, PAYMENT_TIMEOUT, PAYMENT_FAILED -> BookingStatus.PENDING_PAYMENT;
            case NO_SHOW -> BookingStatus.PENDING_CHECKIN;
            case FINISHED_EARLY, ENDED -> BookingStatus.IN_USE;
            case REFUND_SUCCEEDED -> BookingStatus.CANCELLED;
        };
        if (expected != allowed) return false;
        if (reason == ReleaseReason.REFUND_SUCCEEDED) {
            boolean released = update(c, "DELETE s FROM slot_lock s JOIN booking b USING (booking_id) JOIN refund f USING (booking_id) WHERE s.booking_id=? AND b.booking_status=?",
                    id, expected.name()) > 0;
            if (released) recordRelease(c,id,reason);
            return released;
        }
        BookingStatus next = expected == BookingStatus.IN_USE ? BookingStatus.FINISHED : BookingStatus.CANCELLED;
        if (!changeStatus(c, id, expected, next)) return false;
        update(c, "DELETE FROM slot_lock WHERE booking_id=?", id);
        recordRelease(c,id,reason);
        return true;
    }

    private void recordRelease(Connection c,long id,ReleaseReason reason) throws SQLException {
        Sql.update(c,"INSERT INTO booking_outcome(booking_id,release_reason,released_at) VALUES (?,?,?)",
                id,reason.name(),now());
    }

    private boolean changeStatus(Connection c, long id, BookingStatus expected, BookingStatus next) throws SQLException {
        return update(c, "UPDATE booking SET booking_status=? WHERE booking_id=? AND booking_status=?",
                next.name(), id, expected.name()) == 1;
    }

    private BookingView owned(Connection c, long id, long user) throws SQLException {
        return findBooking(c, id).filter(o -> o.userId() == user)
                .orElseThrow(() -> new IllegalArgumentException("找不到本人订单"));
    }

    private String orders(Connection c, long user) throws SQLException {
        StringBuilder output = new StringBuilder();
        try (var p = query(c, "SELECT b.booking_id,b.room_id,b.start_time,b.end_time,b.total_amount,b.booking_status,p.pay_amount,f.refund_amount FROM booking b LEFT JOIN payment p USING(booking_id) LEFT JOIN refund f USING(booking_id) WHERE b.user_id=? ORDER BY b.booking_id DESC", user);
             var row = p.executeQuery()) {
            while (row.next()) {
                output.append("%d | 房间 %d | %s 至 %s | %s 元 | %s".formatted(
                        row.getLong(1), row.getLong(2), row.getString(3), row.getString(4), row.getString(5),
                        switch (BookingStatus.valueOf(row.getString(6))) {
                            case PENDING_PAYMENT -> "待支付";
                            case PENDING_CHECKIN -> "待签到";
                            case IN_USE -> "使用中";
                            case CANCELLED -> "已取消";
                            case FINISHED -> "已结束";
                        }));
                if (row.getString(7) != null) output.append(" | 已付 ").append(row.getString(7));
                if (row.getString(8) != null) output.append(" | 已退 ").append(row.getString(8));
                output.append('\n');
            }
        }
        return output.isEmpty() ? "暂无预约订单" : output.toString();
    }

    private String credit(Connection c, long user) throws SQLException {
        try (var p = query(c, "SELECT credit_score,account_status FROM user WHERE user_id=?", user); var row = p.executeQuery()) {
            require(row.next(), "用户不存在");
            return "信用分 " + row.getInt(1) + "，账号状态 " + row.getString(2);
        }
    }

    private static final String RULE_QUERY = """
            SELECT v.*,COALESCE(d.max_booking_hours,v.daily_slots) AS max_booking_hours,
                COALESCE(d.refund_cutoff_hours,24) AS refund_cutoff_hours,
                COALESCE(d.refund_early_percent,100) AS refund_early_percent,
                COALESCE(d.fulfillment_bonus,2) AS fulfillment_bonus,
                COALESCE(d.review_bonus,1) AS review_bonus,
                COALESCE(d.overstay_deduct,10) AS overstay_deduct,
                COALESCE(d.untidy_deduct,10) AS untidy_deduct
            FROM rule_version v LEFT JOIN rule_details d USING(rule_version_id)
            """;

    private java.util.Map<String,Object> ruleDetails(Connection c, boolean lock) throws SQLException {
        return Sql.one(c,RULE_QUERY+" ORDER BY rule_version_id DESC LIMIT 1"+(lock?" FOR UPDATE":""));
    }

    private record Rule(long id, int days, int slots, BigDecimal price, int refundPercent,
                        int maxHours, int refundCutoff, int earlyRefundPercent) {}

    private Rule rule(Connection c, Long id) throws SQLException {
        try (var p = id == null
                ? query(c, RULE_QUERY + " ORDER BY rule_version_id DESC LIMIT 1")
                : query(c, RULE_QUERY + " WHERE v.rule_version_id=?", id);
             var row = p.executeQuery()) {
            if (!row.next()) throw new SQLException("缺少预约规则");
            return new Rule(row.getLong("rule_version_id"), row.getInt("advance_days"), row.getInt("daily_slots"),
                    row.getBigDecimal("hourly_price"), row.getInt("refund_percent"),row.getInt("max_booking_hours"),
                    row.getInt("refund_cutoff_hours"),row.getInt("refund_early_percent"));
        }
    }

    private void audit(Connection c,long actor,String action,long target,String note,LocalDateTime now) throws SQLException {
        Sql.update(c,"INSERT INTO administrative_action(actor_user_id,action_type,target_id,note,created_at) VALUES (?,?,?,?,?)",
                actor,action,target,note,now);
    }

    private static LocalDateTime now() { return LocalDateTime.now(ZONE); }

    private static String parameter(HttpServletRequest r, String name) {
        String value = r.getParameter(name);
        require(value != null && !value.isBlank(), "请填写 " + name);
        return value.trim();
    }

    private static long number(HttpServletRequest r, String name) {
        return Long.parseLong(parameter(r, name));
    }

    private static int integer(HttpServletRequest r, String name, int min, int max) {
        int value = Integer.parseInt(parameter(r, name));
        require(value >= min && value <= max, name + " 超出允许范围");
        return value;
    }

    private static int optionalInteger(HttpServletRequest r, java.util.Map<String,Object> current, String name, int min, int max) {
        if (r.getParameter(name) == null || r.getParameter(name).isBlank()) return ((Number)current.get(name)).intValue();
        return integer(r,name,min,max);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static PreparedStatement query(Connection c, String sql, Object... values) throws SQLException {
        var p = c.prepareStatement(sql);
        try {
            bind(p, values);
            return p;
        } catch (SQLException ex) {
            p.close();
            throw ex;
        }
    }

    private static int update(Connection c, String sql, Object... values) throws SQLException {
        try (var p = query(c, sql, values)) {
            return p.executeUpdate();
        }
    }

    private static void bind(PreparedStatement p, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
    }
}

