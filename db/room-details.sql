USE studyroom;

CREATE TABLE IF NOT EXISTS room_profile (
    room_id BIGINT NOT NULL PRIMARY KEY,
    has_projector BOOLEAN NOT NULL DEFAULT FALSE,
    has_whiteboard BOOLEAN NOT NULL DEFAULT FALSE,
    has_outlets BOOLEAN NOT NULL DEFAULT FALSE,
    has_multimedia BOOLEAN NOT NULL DEFAULT FALSE,
    equipment_details VARCHAR(200) NULL,
    usage_rules VARCHAR(500) NULL,
    panorama_url VARCHAR(512) NULL,
    CONSTRAINT fk_room_profile FOREIGN KEY (room_id) REFERENCES room(room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Upgrade databases that already have the original room_profile table.
SET @room_profile_ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'room_profile' AND column_name = 'has_multimedia'
), 'DO 0', 'ALTER TABLE room_profile ADD COLUMN has_multimedia BOOLEAN NOT NULL DEFAULT FALSE');
PREPARE room_profile_upgrade FROM @room_profile_ddl;
EXECUTE room_profile_upgrade;
DEALLOCATE PREPARE room_profile_upgrade;

SET @room_profile_ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'room_profile' AND column_name = 'equipment_details'
), 'DO 0', 'ALTER TABLE room_profile ADD COLUMN equipment_details VARCHAR(200) NULL');
PREPARE room_profile_upgrade FROM @room_profile_ddl;
EXECUTE room_profile_upgrade;
DEALLOCATE PREPARE room_profile_upgrade;

-- Course demo inventory. Match known demo rooms and preserve any saved profiles.
INSERT INTO room_profile
    (room_id, has_projector, has_whiteboard, has_outlets, has_multimedia, equipment_details, usage_rules)
SELECT r.room_id, d.projector, d.whiteboard, d.outlets, d.multimedia, d.equipment, d.rules
FROM room AS r
JOIN (
    SELECT 'A101 小组研讨间' AS room_name, 'A101' AS room_code, 1 AS projector, 1 AS whiteboard,
           1 AS outlets, 0 AS multimedia, '桌面配有 4 个电源位；投影仪使用 HDMI 接口。' AS equipment,
           '使用结束后擦净白板、关闭投影仪，将桌椅复位并带走个人物品。' AS rules
    UNION ALL SELECT 'A201 大型研讨间', 'A201', 1, 1, 1, 1,
           '配 1 套 HDMI 投影、1 对壁挂音箱，桌边有两个电源区。',
           '使用音箱时请关闭房门。离开前关闭投影和音箱，清理桌面。'
    UNION ALL SELECT 'A105 小组研讨间', 'A105', 0, 1, 1, 0,
           '配 1 块移动白板和 4 个电源位。',
           '请使用可擦白板笔，结束后擦净白板并把椅子推回桌下。'
    UNION ALL SELECT 'A208 小组研讨间', 'A208', 1, 1, 1, 0,
           '配 HDMI 投影仪、移动白板和 6 个电源位。',
           '拔下个人设备后关闭投影仪，归还转接线并擦净白板。'
    UNION ALL SELECT 'B203 大型研讨间', 'B203', 1, 1, 1, 0,
           '配吊装投影仪、墙面白板和两排地面插座。',
           '讨论期间请勿占用过道。离开前关闭投影并恢复桌椅位置。'
    UNION ALL SELECT 'B407 大型研讨间', 'B407', 1, 1, 1, 1,
           '配讲台投影、墙面白板、音箱和 1 支有线麦克风。',
           '使用麦克风时请关闭房门，结束后关闭音响并整理讲台。'
    UNION ALL SELECT 'C112 多媒体讨论室', 'C112', 0, 1, 1, 1,
           '配 65 英寸显示屏、会议摄像头和 USB 扬声麦克风。',
           '视频会议结束后退出会议账号，关闭显示屏并将遥控器放回桌面。'
    UNION ALL SELECT 'C306 多媒体讨论室', 'C306', 1, 1, 1, 1,
           '配 HDMI 投影仪、会议摄像头和 2 支无线麦克风。',
           '结束后退出个人账号，关闭投影电源，归还麦克风。'
    UNION ALL SELECT 'D204 学术会议室', 'D204', 1, 1, 1, 1,
           '配投影仪、移动白板、讲台音响和有线麦克风。',
           '分享活动结束后请关闭讲台设备，并恢复原有桌椅布置。'
    UNION ALL SELECT 'D310 学术会议室', 'D310', 1, 0, 1, 1,
           '配投影仪、讲台音响和 2 支无线麦克风。',
           '请保持出入口畅通。离开前关闭音响，将麦克风放回充电座。'
    UNION ALL SELECT 'E108 安静自习间', 'E108', 0, 0, 1, 0,
           '学习桌配台灯和 2 个电源位。',
           '请保持安静。离开前关闭台灯，带走个人物品。'
    UNION ALL SELECT 'E215 安静自习间', 'E215', 0, 1, 1, 0,
           '配小型白板、台灯和 3 个电源位。',
           '请将手机设为静音，离开前关闭台灯并擦净白板。'
    UNION ALL SELECT 'F201 创新协作室', 'F201', 0, 1, 1, 1,
           '配双面移动白板、55 英寸无线投屏显示屏和桌边插座。',
           '使用投屏后请断开个人设备，擦净白板并整理走线。'
    UNION ALL SELECT 'F402 创新协作室', 'F402', 1, 1, 1, 1,
           '配 HDMI 投影仪、双面移动白板、桌面摄像头和音箱。',
           '使用结束后关闭投影、摄像头和音箱，白板与桌椅恢复原位。'
) AS d ON d.room_name = r.room_name AND RIGHT(r.location, 4) = d.room_code
WHERE NOT EXISTS (SELECT 1 FROM room_profile p WHERE p.room_id = r.room_id)
ON DUPLICATE KEY UPDATE room_id = room_profile.room_id;
