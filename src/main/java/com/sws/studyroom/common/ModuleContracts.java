package com.sws.studyroom.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

public final class ModuleContracts {
    private ModuleContracts() {
    }

    public enum ListingStatus { LISTED, UNLISTED }
    public enum MaintenanceStatus { NORMAL, MAINTENANCE }
    public enum BookingStatus { PENDING_PAYMENT, PENDING_CHECKIN, IN_USE, CANCELLED, FINISHED }
    public enum ReleaseReason { UNPAID_CANCELLED, PAYMENT_TIMEOUT, REFUND_SUCCEEDED,
        NO_SHOW, FINISHED_EARLY, ENDED }

    public record RoomView(long roomId, ListingStatus listingStatus,
                           MaintenanceStatus maintenanceStatus) {
    }

    public record BookingView(long bookingId, long userId, long roomId,
                              LocalDateTime startTime, LocalDateTime endTime,
                              BookingStatus bookingStatus, long ruleVersionId) {
    }

    public interface RoomAccess {
        Optional<RoomView> findRoom(Connection transaction, long roomId) throws SQLException;
    }

    public interface BookingAccess {
        Optional<BookingView> findBooking(Connection transaction, long bookingId)
                throws SQLException;

        // 返回 false 表示订单不存在，或当前状态不符合 expectedStatus。
        boolean beginUsage(Connection transaction, long bookingId, BookingStatus expectedStatus)
                throws SQLException;

        // 仅当预期状态匹配且原因符合业务条件才变更状态并删除该订单的占用。
        // 已处理的事件或状态不匹配返回 false，不重复执行。
        boolean release(Connection transaction, long bookingId, BookingStatus expectedStatus,
                        ReleaseReason reason, String eventId) throws SQLException;
    }

    public interface CreditAccess {
        int findScore(Connection transaction, long userId) throws SQLException;

        // eventId 已记账时返回 false。一次记账须同时写流水和更新账户余额。
        boolean applyChange(Connection transaction, long userId, String eventId, int delta,
                            String reason) throws SQLException;
    }
}

