USE studyroom;

CREATE TABLE IF NOT EXISTS message (
    message_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    title VARCHAR(80) NOT NULL,
    content VARCHAR(500) NOT NULL,
    sent_at DATETIME NOT NULL,
    read_at DATETIME NULL,
    UNIQUE KEY uk_message_event (user_id, event_id, message_type),
    KEY idx_message_inbox (user_id, message_id),
    CONSTRAINT fk_message_user FOREIGN KEY (user_id) REFERENCES `user`(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
