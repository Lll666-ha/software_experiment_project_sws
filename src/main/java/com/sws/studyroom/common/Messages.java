package com.sws.studyroom.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Map;

public final class Messages {
    private Messages() {}

    public static void send(Connection c, long userId, String eventId, String type,
                            String title, String content, LocalDateTime when) throws SQLException {
        Sql.update(c, """
            INSERT INTO message(user_id,event_id,message_type,title,content,sent_at) VALUES (?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE message_id=message_id
            """, userId,eventId,type,title,content,when);
    }

    public static Map<String, Object> inbox(Connection c, long userId, ListPage page) throws SQLException {
        Number total = (Number) Sql.one(c, "SELECT COUNT(*) AS total FROM message WHERE user_id=?", userId).get("total");
        var items = Sql.rows(c, """
            SELECT message_id,message_type,title,content,sent_at,read_at
            FROM message WHERE user_id=? ORDER BY message_id DESC LIMIT ? OFFSET ?
            """, userId,page.size(),page.offset());
        return page.result(items,total);
    }

    public static void markRead(Connection c, long userId, long messageId, LocalDateTime when) throws SQLException {
        Sql.update(c, "UPDATE message SET read_at=? WHERE user_id=? AND message_id=? AND read_at IS NULL",
                when,userId,messageId);
    }
}
