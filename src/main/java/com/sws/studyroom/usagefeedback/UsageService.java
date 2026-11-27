package com.sws.studyroom.usagefeedback;

import com.sws.studyroom.common.ModuleContracts.*;
import com.sws.studyroom.common.SessionIdentity;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.sws.studyroom.common.Sql.*;

/** All callers own the transaction. Locks use user -> room -> booking -> detail order. */
public final class UsageService implements CreditAccess {
    private final BookingAccess bookings;
    private final Clock clock;
    public UsageService(BookingAccess bookings, Clock clock) { this.bookings = bookings; this.clock = clock; }
    public LocalDateTime now() { return LocalDateTime.now(clock); }
    public static final class Failure extends IllegalArgumentException {
        public final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
    }
    private static void check(boolean ok, int status, String message) { if (!ok) throw new Failure(status, message); }
    private BookingView lock(Connection c, long id, Long owner) throws SQLException {
        BookingView first = bookings.findBooking(c, id).orElseThrow(() -> new Failure(404, "预约不存在"));
        check(owner == null || first.userId() == owner, 403, "只能操作本人预约");
        one(c, "SELECT user_id FROM `user` WHERE user_id=? FOR UPDATE", first.userId());
        one(c, "SELECT room_id FROM room WHERE room_id=? FOR UPDATE", first.roomId());
        one(c, "SELECT booking_id FROM booking WHERE booking_id=? FOR UPDATE", id);
        return bookings.findBooking(c, id).orElseThrow();
    }
    private Map<String, Object> rule(Connection c, BookingView b) throws SQLException {
        var rule = one(c, "SELECT * FROM rule_version WHERE rule_version_id=?", b.ruleVersionId());
        if (rule == null) throw new SQLException("缺少订单规则快照"); return rule;
    }
    private static int value(Map<String, Object> r, String key) { return ((Number)r.get(key)).intValue(); }
    private static long id(Map<String, Object> r, String key) { return Long.parseLong(r.get(key).toString()); }
    private static LocalDateTime time(Object value) { return LocalDateTime.parse(value.toString().replace(' ', 'T')); }
    public Map<String, Object> checkIn(Connection c, long bookingId, long user) throws SQLException {
        var b = lock(c, bookingId, user);
        var previous = one(c, "SELECT * FROM `usage` WHERE booking_id=?", bookingId);
        if (previous != null && previous.get("sign_in_time") != null) {
            check(b.bookingStatus() == BookingStatus.IN_USE, 409, "订单已结束"); return previous;
        }
        check(b.bookingStatus() == BookingStatus.PENDING_CHECKIN, 409, "仅已支付待签到订单可签到");
        var r = rule(c, b); var now = now();
        check(!now.isBefore(b.startTime().minusMinutes(value(r, "checkin_early_minutes")))
                && !now.isAfter(b.startTime().plusMinutes(value(r, "checkin_late_minutes")))
                && now.isBefore(b.endTime()), 409, "不在允许的签到时间内");
        check(bookings.beginUsage(c, bookingId, BookingStatus.PENDING_CHECKIN), 409, "订单已处理");
        insert(c, "INSERT INTO `usage`(booking_id,sign_in_time,door_open_result,gate_request_id,door_attempt_count) VALUES (?,?,'SUCCESS',?,1)",
                bookingId, now, "gate:" + bookingId);
        // The course gate adapter is synchronous and simulated. No network/hardware is called in the transaction.
        return one(c, "SELECT * FROM `usage` WHERE booking_id=?", bookingId);
    }
    public Map<String, Object> retryDoor(Connection c, long bookingId, long user) throws SQLException {
        var b = lock(c, bookingId, user);
        check(b.bookingStatus() == BookingStatus.IN_USE && now().isBefore(b.endTime()), 409, "订单不在有效使用期");
        var u = one(c, "SELECT * FROM `usage` WHERE booking_id=?", bookingId);
        check(u != null && u.get("sign_in_time") != null, 409, "尚未签到");
        if ("FAILED".equals(u.get("door_open_result"))) {
            update(c, "UPDATE `usage` SET door_open_result='SUCCESS',door_attempt_count=door_attempt_count+1 WHERE booking_id=?", bookingId);
        }
        return one(c, "SELECT * FROM `usage` WHERE booking_id=?", bookingId);
    }
    public Map<String, Object> finish(Connection c, long bookingId, long user) throws SQLException {
        var b = lock(c, bookingId, user);
        if (b.bookingStatus() == BookingStatus.FINISHED) return one(c, "SELECT * FROM `usage` WHERE booking_id=?", bookingId);
        check(b.bookingStatus() == BookingStatus.IN_USE, 409, "只能结束已签到的订单");
        var u = one(c, "SELECT * FROM `usage` WHERE booking_id=?", bookingId);
        check(u != null && u.get("sign_in_time") != null, 409, "缺少签到记录");
        var now = now(); var r = rule(c, b);
        boolean early = now.isBefore(b.endTime());
        check(bookings.release(c, bookingId, BookingStatus.IN_USE,
                early ? ReleaseReason.FINISHED_EARLY : ReleaseReason.ENDED, "finish:" + bookingId), 409, "订单已处理");
        update(c, "UPDATE `usage` SET actual_end_time=? WHERE booking_id=?", early ? now : b.endTime(), bookingId);
        if (early && value(r, "early_release_deduct") > 0)
            changeCredit(c, user, "early:" + bookingId, -value(r, "early_release_deduct"), "EARLY_RELEASE", value(r, "credit_suspend_days"));
        return one(c, "SELECT * FROM `usage` WHERE booking_id=?", bookingId);
    }
    /** One candidate per transaction; safe against concurrent scans and user actions. */
    public void expire(Connection c, long bookingId) throws SQLException {
        var b = lock(c, bookingId, null); var now = now(); var r = rule(c, b);
        if (b.bookingStatus() == BookingStatus.PENDING_PAYMENT) {
            var row = one(c, "SELECT pay_deadline FROM booking WHERE booking_id=?", bookingId);
            if (!now.isBefore(time(row.get("pay_deadline"))))
                bookings.release(c, bookingId, BookingStatus.PENDING_PAYMENT, ReleaseReason.PAYMENT_TIMEOUT, "expire:" + bookingId);
        } else if (b.bookingStatus() == BookingStatus.PENDING_CHECKIN
                && now.isAfter(b.startTime().plusMinutes(value(r, "checkin_late_minutes")))) {
            if (bookings.release(c, bookingId, BookingStatus.PENDING_CHECKIN, ReleaseReason.NO_SHOW, "timeout:" + bookingId)) {
                int old = findScore(c, b.userId());
                changeCredit(c, b.userId(), "timeout:" + bookingId, -value(r, "timeout_deduct"), "TIMEOUT", value(r, "credit_suspend_days"));
                insert(c, "INSERT INTO violation(booking_id,user_id,violation_type,credit_deduct,is_revoked,created_at) VALUES (?,?,'NO_SHOW',?,0,?)",
                        bookingId, b.userId(), old - findScore(c, b.userId()), now);
                com.sws.studyroom.common.Messages.send(c,b.userId(),"booking:"+bookingId,"NO_SHOW",
                        "未签到，房间已释放","订单 #"+bookingId+" 超过签到时限，扣除 "+(old-findScore(c,b.userId()))+" 分。",now);
            }
        } else if (b.bookingStatus() == BookingStatus.IN_USE && !now.isBefore(b.endTime())) {
            if (bookings.release(c, bookingId, BookingStatus.IN_USE, ReleaseReason.ENDED, "ended:" + bookingId))
                update(c, "UPDATE `usage` SET actual_end_time=? WHERE booking_id=? AND actual_end_time IS NULL", b.endTime(), bookingId);
        }
    }
    @Override public int findScore(Connection c, long user) throws SQLException {
        var row = one(c, "SELECT credit_score FROM `user` WHERE user_id=?", user);
        check(row != null, 404, "用户不存在"); return value(row, "credit_score");
    }
    @Override public boolean applyChange(Connection c, long user, String eventId, int delta, String reason) throws SQLException {
        return changeCredit(c, user, eventId, delta, reason, 3);
    }
    private boolean changeCredit(Connection c, long user, String event, int delta, String reason, int suspendDays) throws SQLException {
        check(event != null && !event.isBlank() && event.length() <= 128 && Math.abs((long)delta) <= 300, 400, "信用变更参数不合法");
        check(reason != null && !reason.isBlank() && reason.length() <= 500, 400, "请填写有效的信用变更原因");
        var row = one(c, "SELECT credit_score FROM `user` WHERE user_id=? FOR UPDATE", user);
        check(row != null, 404, "用户不存在");
        var previous = one(c, "SELECT * FROM credit_ledger WHERE event_id=?", event);
        if (previous != null) {
            check(id(previous, "user_id") == user && previous.get("requested_delta") != null && value(previous, "requested_delta") == delta
                    && reason.equals(previous.get("reason")), 409, "事件编号已用于其他信用操作"); return false;
        }
        int old = value(row, "credit_score"), score = Math.max(0, Math.min(300, old + delta));
        update(c, "UPDATE `user` SET credit_score=? WHERE user_id=?", score, user);
        if (old > 0 && score == 0)
            update(c, "UPDATE `user` SET credit_suspended_until=GREATEST(COALESCE(credit_suspended_until,?),?) WHERE user_id=?",
                    now().plusDays(suspendDays), now().plusDays(suspendDays), user);
        insert(c, "INSERT INTO credit_ledger(user_id,delta_score,requested_delta,score_after,reason,created_at,event_id) VALUES (?,?,?,?,?,?,?)",
                user, score - old, delta, score, reason, now(), event);
        return true;
    }
    public Map<String, Object> credit(Connection c, long user) throws SQLException {
        var row = one(c, "SELECT user_id,credit_score,account_status,credit_suspended_until FROM `user` WHERE user_id=?", user);
        check(row != null, 404, "用户不存在");
        row.put("ledger", rows(c, "SELECT ledger_id,event_id,delta_score,score_after,reason,created_at FROM credit_ledger WHERE user_id=? ORDER BY ledger_id DESC LIMIT 100", user));
        row.put("violations", rows(c, "SELECT * FROM violation WHERE user_id=? ORDER BY violation_id DESC LIMIT 100", user));
        return row;
    }
    public Map<String, Object> review(Connection c, long bookingId, long user, int score1, int score2, String comment) throws SQLException {
        var b = lock(c, bookingId, user);
        check(b.bookingStatus() == BookingStatus.FINISHED, 409, "仅已结束的本人订单可以评价");
        check(score1 >= 1 && score1 <= 5 && score2 >= 1 && score2 <= 5 && comment.length() <= 500, 400, "评分需为1至5，评价最多500字");
        var old = one(c, "SELECT * FROM review WHERE booking_id=?", bookingId);
        if (old != null) return old;
        var usage = one(c, "SELECT actual_end_time FROM `usage` WHERE booking_id=?", bookingId);
        check(usage != null && usage.get("actual_end_time") != null, 409, "缺少实际结束记录");
        var end = time(usage.get("actual_end_time"));
        check(!now().isBefore(end) && !now().isAfter(end.plusHours(value(rule(c,b), "review_window_hours"))), 409, "已超过评价期限");
        insert(c, "INSERT INTO review(booking_id,user_id,score_1,score_2,comment,created_at) VALUES (?,?,?,?,?,?)", bookingId,user,score1,score2,comment,now());
        return one(c, "SELECT * FROM review WHERE booking_id=?", bookingId);
    }
    public Map<String, Object> fault(Connection c, long room, long user, String description) throws SQLException {
        check(!description.isBlank() && description.length() <= 500, 400, "故障描述需为1至500字");
        var roomData=one(c, "SELECT room_id,manager_user_id FROM room WHERE room_id=?", room);
        check(roomData != null, 404, "房间不存在");
        long fault = insert(c, "INSERT INTO fault(room_id,user_id,fault_desc,fault_status,created_at) VALUES (?,?,?,'PENDING',?)", room,user,description,now());
        if (roomData.get("manager_user_id") != null)
            com.sws.studyroom.common.Messages.send(c,id(roomData,"manager_user_id"),"fault:"+fault,
                    "FAULT_REPORTED","收到新的房间报障","房间 #"+room+" 有一条待处理报障。请到管理工作台查看。",now());
        return one(c, "SELECT * FROM fault WHERE fault_id=?", fault);
    }
    public Map<String, Object> appeal(Connection c, long bookingId, long violation, long user, String reason) throws SQLException {
        lock(c, bookingId, user);
        check(!reason.isBlank() && reason.length() <= 500, 400, "申诉理由需为1至500字");
        var v = one(c, "SELECT * FROM violation WHERE violation_id=? AND booking_id=? AND user_id=?", violation,bookingId,user);
        check(v != null && value(v,"is_revoked") == 0, 409, "没有可申诉的违约记录");
        var old = one(c, "SELECT * FROM appeal WHERE booking_id=? AND appeal_status='PENDING' ORDER BY appeal_id LIMIT 1", bookingId);
        if (old != null) return old;
        long result = insert(c, "INSERT INTO appeal(booking_id,user_id,violation_id,appeal_reason,appeal_status,created_at) VALUES (?,?,?,?,'PENDING',?)", bookingId,user,violation,reason,now());
        return one(c,"SELECT * FROM appeal WHERE appeal_id=?",result);
    }
    private void manageRoom(Connection c, long room, SessionIdentity actor) throws SQLException {
        check(actor.role() != SessionIdentity.Role.USER, 403, "需要管理员权限");
        var r = one(c, "SELECT manager_user_id FROM room WHERE room_id=? FOR UPDATE", room);
        check(r != null, 404, "房间不存在");
        check(actor.role() == SessionIdentity.Role.SUPER_ADMIN || r.get("manager_user_id") != null
                && id(r,"manager_user_id") == actor.userId(), 403, "不在房间管理范围内");
    }
    public Map<String, Object> processAppeal(Connection c, long appealId, SessionIdentity actor, String decision, String opinion) throws SQLException {
        check(Set.of("APPROVED","REJECTED").contains(decision) && !opinion.isBlank() && opinion.length() <= 500, 400, "请填写处理结论和意见");
        var a = one(c,"SELECT * FROM appeal WHERE appeal_id=?",appealId);
        check(a != null,404,"申诉不存在");
        var b = lock(c,id(a,"booking_id"),null); manageRoom(c,b.roomId(),actor);
        a = one(c,"SELECT * FROM appeal WHERE appeal_id=? FOR UPDATE",appealId);
        if (!"PENDING".equals(a.get("appeal_status"))) return a;
        if (decision.equals("APPROVED")) {
            var v = one(c,"SELECT * FROM violation WHERE violation_id=? FOR UPDATE",id(a,"violation_id"));
            if (value(v,"is_revoked") == 0) {
                var original = one(c,"SELECT delta_score FROM credit_ledger WHERE user_id=? AND event_id=?",b.userId(),"timeout:"+b.bookingId());
                if (original != null) changeCredit(c,b.userId(),"appeal:"+id(a,"violation_id"),-value(original,"delta_score"),"APPEAL_RETURN",3);
                update(c,"UPDATE violation SET is_revoked=1 WHERE violation_id=?",id(a,"violation_id"));
                // Simulated course refund: return only the remaining paid balance, once, inside this transaction.
                var paid = one(c,"SELECT pay_amount FROM payment WHERE booking_id=?",b.bookingId());
                if (paid != null && one(c,"SELECT booking_id FROM refund WHERE booking_id=?",b.bookingId()) == null)
                    update(c,"INSERT INTO refund(booking_id,refund_amount,refunded_at) VALUES (?,?,?)",b.bookingId(),paid.get("pay_amount"),now());
            }
        }
        update(c,"UPDATE appeal SET appeal_status=?,admin_opinion=?,handled_by=?,handled_at=? WHERE appeal_id=?",decision,opinion,actor.userId(),now(),appealId);
        com.sws.studyroom.common.Messages.send(c,b.userId(),"appeal:"+appealId,"APPEAL_REVIEWED",
                "申诉复核已完成","订单 #"+b.bookingId()+" 的申诉"+(decision.equals("APPROVED")?"已通过":"已驳回")+"。请到反馈与申诉查看详情。",now());
        return one(c,"SELECT * FROM appeal WHERE appeal_id=?",appealId);
    }
    public Map<String, Object> processFault(Connection c, long faultId, SessionIdentity actor, String next, String opinion) throws SQLException {
        check(Set.of("REPAIRING","RESOLVED").contains(next) && !opinion.isBlank() && opinion.length() <= 500,400,"请填写处理状态和意见");
        var first = one(c,"SELECT room_id FROM fault WHERE fault_id=?",faultId); check(first != null,404,"故障不存在");
        long room = id(first,"room_id"); manageRoom(c,room,actor);
        var f = one(c,"SELECT * FROM fault WHERE fault_id=? FOR UPDATE",faultId);
        if (next.equals(f.get("fault_status"))) return f;
        check(next.equals("REPAIRING") && "PENDING".equals(f.get("fault_status"))
                || next.equals("RESOLVED") && "REPAIRING".equals(f.get("fault_status")),409,"故障状态不能跳转");
        if (next.equals("REPAIRING")) {
            // Restore the prior manual maintenance setting only when the last blocking fault is resolved.
            update(c,"UPDATE room SET maintenance_before_fault=maintenance_status,maintenance_status='MAINTENANCE',fault_maintenance_active=1 WHERE room_id=? AND fault_maintenance_active=0",room);
        }
        update(c,"UPDATE fault SET fault_status=?,admin_opinion=?,handled_by=?,handled_at=?,finished_at=? WHERE fault_id=?",
                next,opinion,actor.userId(),now(),next.equals("RESOLVED")?now():null,faultId);
        if (next.equals("RESOLVED"))
            update(c,"UPDATE room SET maintenance_status=maintenance_before_fault,fault_maintenance_active=0 WHERE room_id=? AND NOT EXISTS (SELECT 1 FROM fault WHERE room_id=? AND fault_status<>'RESOLVED')",room,room);
        return one(c,"SELECT * FROM fault WHERE fault_id=?",faultId);
    }
    public List<Map<String,Object>> adminList(Connection c, SessionIdentity actor, String kind) throws SQLException {
        check(actor.role() != SessionIdentity.Role.USER,403,"需要管理员权限");
        String table, join, sort;
        if (kind.equals("faults")) { table="fault"; join="JOIN room r ON r.room_id=t.room_id"; sort="fault_id"; }
        else { table="appeal"; join="JOIN booking b ON b.booking_id=t.booking_id JOIN room r ON r.room_id=b.room_id"; sort="appeal_id"; }
        return actor.role() == SessionIdentity.Role.SUPER_ADMIN
                ? rows(c,"SELECT t.* FROM "+table+" t "+join+" ORDER BY t."+sort+" DESC LIMIT 100")
                : rows(c,"SELECT t.* FROM "+table+" t "+join+" WHERE r.manager_user_id=? ORDER BY t."+sort+" DESC LIMIT 100",actor.userId());
    }
}
