-- Run after the studyroom schema has been initialized. Save and import as UTF-8.
-- All locations below are fictional. Existing rooms and bookings are preserved.
USE studyroom;

START TRANSACTION;

INSERT INTO room
    (room_name, location, capacity, opening_hours, listing_status, maintenance_status, manager_user_id)
SELECT
    demo.room_name,
    demo.location,
    demo.capacity,
    demo.opening_hours,
    'LISTED',
    'NORMAL',
    (SELECT user_id FROM `user` WHERE username = 'room_admin' AND role = 'ROOM_ADMIN' LIMIT 1)
FROM (
    SELECT 'A105 小组研讨间' AS room_name, '明理图书馆 1 层 A105' AS location, 4 AS capacity, '08:00-22:00' AS opening_hours
    UNION ALL SELECT 'A208 小组研讨间', '明理图书馆 2 层 A208', 6, '08:00-22:00'
    UNION ALL SELECT 'B203 大型研讨间', '知行教学楼 2 层 B203', 12, '08:00-21:00'
    UNION ALL SELECT 'B407 大型研讨间', '知行教学楼 4 层 B407', 16, '09:00-21:00'
    UNION ALL SELECT 'C112 多媒体讨论室', '博学楼 1 层 C112', 8, '09:00-21:00'
    UNION ALL SELECT 'C306 多媒体讨论室', '博学楼 3 层 C306', 14, '09:00-20:00'
    UNION ALL SELECT 'D204 学术会议室', '学术交流中心 2 层 D204', 20, '09:00-20:00'
    UNION ALL SELECT 'D310 学术会议室', '学术交流中心 3 层 D310', 24, '09:00-18:00'
    UNION ALL SELECT 'E108 安静自习间', '求知书院 1 层 E108', 2, '08:00-22:00'
    UNION ALL SELECT 'E215 安静自习间', '求知书院 2 层 E215', 3, '08:00-22:00'
    UNION ALL SELECT 'F201 创新协作室', '创客中心 2 层 F201', 8, '10:00-21:00'
    UNION ALL SELECT 'F402 创新协作室', '创客中心 4 层 F402', 10, '09:00-21:00'
) AS demo
WHERE NOT EXISTS (
    SELECT 1 FROM room AS existing
    WHERE existing.room_name = demo.room_name AND existing.location = demo.location
);

SELECT ROW_COUNT() AS rooms_added;
-- Keep the two original room IDs, bookings and other room settings.
UPDATE room SET location = CASE room_id
    WHEN 9101 THEN '明理图书馆 1 层 A101'
    WHEN 9102 THEN '明理图书馆 2 层 A201'
END
WHERE room_id IN (9101, 9102);

COMMIT;
