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

-- 演示账号由页面注册获得。需要房管账号时，先注册 room_admin，再执行：
-- UPDATE `user` SET role = 'ROOM_ADMIN' WHERE username = 'room_admin';
-- 实际环境不应开放页面修改角色。

INSERT IGNORE INTO room
    (room_id, room_name, location, capacity, opening_hours, listing_status, maintenance_status)
VALUES
    (9101, 'A101 小组研讨间', '图书馆一层 A101', 4, '08:00-22:00', 'LISTED', 'NORMAL'),
    (9102, 'A201 大型研讨间', '图书馆二层 A201', 8, '08:00-22:00', 'LISTED', 'NORMAL'),
    (9103, 'B301 设备维护间', '综合楼三层 B301', 12, '09:00-18:00', 'LISTED', 'MAINTENANCE'),
    (9104, 'B302 暂未开放间', '综合楼三层 B302', 6, '09:00-18:00', 'UNLISTED', 'NORMAL');
