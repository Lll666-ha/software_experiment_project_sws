-- 只在本地预约测试库运行。正式账号及房间模块接入前使用。
USE studyroom;
CREATE TABLE IF NOT EXISTS user (
    user_id BIGINT PRIMARY KEY,
    account_status VARCHAR(24) NOT NULL DEFAULT 'NORMAL',
    credit_score INT NOT NULL DEFAULT 300,
    credit_suspended_until DATETIME NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS room (
    room_id BIGINT PRIMARY KEY,
    listing_status VARCHAR(24) NOT NULL,
    maintenance_status VARCHAR(24) NOT NULL,
    opening_hours VARCHAR(24) NOT NULL
) ENGINE=InnoDB;
INSERT IGNORE INTO user (user_id) VALUES (9001), (9002), (9003);
INSERT IGNORE INTO room VALUES
    (9101, 'LISTED', 'NORMAL', '08:00-22:00'),
    (9102, 'LISTED', 'NORMAL', '08:00-22:00');

CREATE TABLE IF NOT EXISTS booking_fixture_credit (event_id VARCHAR(70) PRIMARY KEY, user_id BIGINT NOT NULL) ENGINE=InnoDB;
