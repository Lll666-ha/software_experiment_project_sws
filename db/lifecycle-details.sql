USE studyroom;

CREATE TABLE IF NOT EXISTS booking_outcome (
    booking_id BIGINT NOT NULL PRIMARY KEY,
    release_reason VARCHAR(32) NULL,
    released_at DATETIME NULL,
    FOREIGN KEY (booking_id) REFERENCES booking(booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS simulated_refund_request (
    booking_id BIGINT NOT NULL PRIMARY KEY,
    request_no VARCHAR(80) NOT NULL UNIQUE,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(16) NOT NULL,
    reason VARCHAR(200) NULL,
    requested_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    attempts INT NOT NULL DEFAULT 1,
    FOREIGN KEY (booking_id) REFERENCES booking(booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_login_guard (
    user_id BIGINT NOT NULL PRIMARY KEY,
    failures TINYINT NOT NULL DEFAULT 0,
    locked_until DATETIME NULL,
    FOREIGN KEY (user_id) REFERENCES `user`(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account_email (
    user_id BIGINT NOT NULL PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    FOREIGN KEY (user_id) REFERENCES `user`(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rule_details (
    rule_version_id BIGINT NOT NULL PRIMARY KEY,
    max_booking_hours INT NOT NULL,
    refund_cutoff_hours INT NOT NULL,
    refund_early_percent INT NOT NULL,
    fulfillment_bonus INT NOT NULL,
    review_bonus INT NOT NULL,
    overstay_deduct INT NOT NULL,
    untidy_deduct INT NOT NULL,
    FOREIGN KEY (rule_version_id) REFERENCES rule_version(rule_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS administrative_action (
    action_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    actor_user_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    FOREIGN KEY (actor_user_id) REFERENCES `user`(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS feedback_attachment (
    attachment_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_id BIGINT NULL,
    appeal_id BIGINT NULL,
    media_type VARCHAR(32) NOT NULL,
    byte_count INT NOT NULL,
    payload MEDIUMBLOB NOT NULL,
    FOREIGN KEY (review_id) REFERENCES review(review_id) ON DELETE CASCADE,
    FOREIGN KEY (appeal_id) REFERENCES appeal(appeal_id) ON DELETE CASCADE,
    CHECK ((review_id IS NULL) <> (appeal_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
