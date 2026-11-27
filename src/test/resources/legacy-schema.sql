USE studyroom;

CREATE TABLE IF NOT EXISTS `user` (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    phone VARCHAR(20) NULL,
    password_salt CHAR(32) NOT NULL,
    password_hash CHAR(64) NOT NULL,
    role VARCHAR(24) NOT NULL DEFAULT 'USER',
    account_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    credit_score INT NOT NULL DEFAULT 100,
    credit_suspended_until DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_phone (phone),
    CONSTRAINT ck_user_role CHECK (role IN ('USER', 'ROOM_ADMIN', 'SUPER_ADMIN')),
    CONSTRAINT ck_user_status CHECK (account_status IN ('ACTIVE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room (
    room_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_name VARCHAR(80) NOT NULL,
    location VARCHAR(160) NOT NULL,
    capacity INT NOT NULL,
    opening_hours VARCHAR(11) NOT NULL DEFAULT '08:00-22:00',
    listing_status VARCHAR(24) NOT NULL DEFAULT 'UNLISTED',
    maintenance_status VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
    manager_user_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_room_manager FOREIGN KEY (manager_user_id) REFERENCES `user` (user_id),
    CONSTRAINT ck_room_capacity CHECK (capacity > 0),
    CONSTRAINT ck_room_listing CHECK (listing_status IN ('LISTED', 'UNLISTED')),
    CONSTRAINT ck_room_maintenance CHECK (maintenance_status IN ('NORMAL', 'MAINTENANCE')),
    UNIQUE KEY uk_room_name_location (room_name, location),
    INDEX idx_room_available (listing_status, maintenance_status, capacity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 本地演示用预置房管账号：room_admin / Admin@123456。
-- 密码以 PBKDF2WithHmacSHA256（120000 次迭代）保存；生产环境应通过安全的
-- 身份管理流程创建账号，并更换这里的默认密码。
INSERT IGNORE INTO `user`
    (username, phone, password_salt, password_hash, role, account_status)
VALUES
    ('room_admin', '13800000002',
     '6d79526f6f6d41646d696e3230323631',
     '06fed1d39b3c6c536eb54e12beb56b905ec8605f1fb870b1f8929884a70a2e9f',
     'ROOM_ADMIN', 'ACTIVE');

INSERT IGNORE INTO room
    (room_id, room_name, location, capacity, opening_hours, listing_status, maintenance_status)
VALUES
    (9101, 'A101 小组研讨间', '图书馆一层 A101', 4, '08:00-22:00', 'LISTED', 'NORMAL'),
    (9102, 'A201 大型研讨间', '图书馆二层 A201', 8, '08:00-22:00', 'LISTED', 'NORMAL'),
    (9103, 'B301 设备维护间', '综合楼三层 B301', 12, '09:00-18:00', 'LISTED', 'MAINTENANCE'),
    (9104, 'B302 暂未开放间', '综合楼三层 B302', 6, '09:00-18:00', 'UNLISTED', 'NORMAL');

-- 让预置房管可直接演示编辑、上下架与维护 9101/9102。
UPDATE room
SET manager_user_id = (SELECT user_id FROM `user` WHERE username = 'room_admin')
WHERE room_id IN (9101, 9102);

USE studyroom;

CREATE TABLE IF NOT EXISTS rule_version (
 rule_version_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 advance_days INT NOT NULL,
 daily_slots INT NOT NULL,
 hourly_price DECIMAL(10,2) NOT NULL,
 refund_percent INT NOT NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO rule_version VALUES (1,7,2,10.00,80);

CREATE TABLE IF NOT EXISTS booking (
 booking_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 room_id BIGINT NOT NULL,
 start_time DATETIME NOT NULL,
 end_time DATETIME NOT NULL,
 total_amount DECIMAL(10,2) NOT NULL,
 booking_status VARCHAR(24) NOT NULL,
 rule_version_id BIGINT NOT NULL,
 pay_deadline DATETIME NOT NULL,
 FOREIGN KEY (rule_version_id) REFERENCES rule_version(rule_version_id),
 INDEX (user_id, start_time)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS slot_lock (
 occupancy_id BIGINT AUTO_INCREMENT PRIMARY KEY,
 booking_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 room_id BIGINT NOT NULL,
 slot_start DATETIME NOT NULL,
 slot_end DATETIME NOT NULL,
 FOREIGN KEY (booking_id) REFERENCES booking(booking_id),
 UNIQUE (user_id, slot_start),
 UNIQUE (room_id, slot_start)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS payment (
 booking_id BIGINT PRIMARY KEY,
 pay_amount DECIMAL(10,2) NOT NULL,
 paid_at DATETIME NOT NULL,
 FOREIGN KEY (booking_id) REFERENCES booking(booking_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS refund (
 booking_id BIGINT PRIMARY KEY,
 refund_amount DECIMAL(10,2) NOT NULL,
 refunded_at DATETIME NOT NULL,
 FOREIGN KEY (booking_id) REFERENCES booking(booking_id)
) ENGINE=InnoDB;

USE studyroom;
-- usage‑feedback模块：usage、credit_ledger、violation、review、fault、appeal

-- 签到使用记录表
CREATE TABLE IF NOT EXISTS `usage` (
                                       usage_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '签到记录主键',
                                       booking_id BIGINT NOT NULL COMMENT '关联预约订单',
                                       sign_in_time DATETIME NULL COMMENT '实际签到时间',
                                       door_open_result VARCHAR(32) NOT NULL COMMENT '成功 / 失败',
                                       actual_end_time DATETIME NULL COMMENT '实际结束释放时间',
                                       UNIQUE KEY uk_usage_booking(booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签到使用记录表';

-- 信用流水记录表
CREATE TABLE IF NOT EXISTS credit_ledger (
                                             ledger_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                             user_id BIGINT NOT NULL,
                                             violation_id BIGINT NULL,
                                             appeal_id BIGINT NULL,
                                             delta_score INT NOT NULL COMMENT '正数加分、负数扣分',
                                             reason TEXT NOT NULL,
                                             create_time DATETIME NOT NULL,
                                             event_no VARCHAR(128) NOT NULL COMMENT '幂等事件编号',
                                             UNIQUE KEY uk_ledger_eventno(event_no),
                                             INDEX idx_userid(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信用流水记录表';

-- 违规记录表
CREATE TABLE IF NOT EXISTS violation (
                                         violation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                         booking_id BIGINT NOT NULL,
                                         user_id BIGINT NOT NULL,
                                         violation_type VARCHAR(64) NOT NULL COMMENT '超时未签到、违约取消',
                                         credit_deduct INT NOT NULL,
                                         is_revoked TINYINT NOT NULL DEFAULT 0 COMMENT '0未撤销 1已撤销',
                                         create_time DATETIME NOT NULL,
                                         INDEX idx_booking(booking_id),
                                         INDEX idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录表';

-- 评价记录表（双评分，文档要求score_1整洁度，score_2设备完好度）
CREATE TABLE IF NOT EXISTS review (
                                      review_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                      booking_id BIGINT NOT NULL,
                                      user_id BIGINT NOT NULL,
                                      score_1 TINYINT NOT NULL COMMENT '整洁度1‑5',
                                      score_2 TINYINT NOT NULL COMMENT '设备完好度1‑5',
                                      comment TEXT NULL,
                                      create_time DATETIME NOT NULL,
                                      UNIQUE KEY uk_review_booking(booking_id) COMMENT '一个订单只能评价一次',
                                      INDEX idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价记录表';

-- 故障上报记录表
CREATE TABLE IF NOT EXISTS fault (
                                     fault_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                     room_id BIGINT NOT NULL,
                                     user_id BIGINT NOT NULL COMMENT '上报用户',
                                     fault_desc TEXT NOT NULL,
                                     fault_status VARCHAR(32) NOT NULL COMMENT '待受理、维修中、已修复完成',
                                     create_time DATETIME NOT NULL,
                                     finish_time DATETIME NULL,
                                     INDEX idx_room(room_id),
                                     INDEX idx_status(fault_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='故障上报记录表';

-- 申诉记录表
CREATE TABLE IF NOT EXISTS appeal (
                                      appeal_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                                      booking_id BIGINT NOT NULL,
                                      user_id BIGINT NOT NULL,
                                      violation_id BIGINT NOT NULL COMMENT '关联被申诉违规记录',
                                      appeal_reason TEXT NOT NULL,
                                      admin_opinion TEXT NULL,
                                      appeal_status VARCHAR(32) NOT NULL COMMENT '待复核、申诉通过、申诉驳回',
                                      create_time DATETIME NOT NULL,
                                      handle_time DATETIME NULL,
                                      INDEX idx_violation(violation_id),
                                      INDEX idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申诉记录表';



