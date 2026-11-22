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
