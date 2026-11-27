-- Isolated course-test fixtures. Run the real module schema first.
USE studyroom;
INSERT IGNORE INTO `user`(user_id,username,password_salt,password_hash,role,account_status) VALUES
(9001,'booking_test_9001','0123456789abcdef0123456789abcdef','dd52b37acee881c52c0db04262516309c0ecdea5873568c9b99af0e1696fb480','USER','ACTIVE'),
(9002,'booking_test_9002','0123456789abcdef0123456789abcdef','dd52b37acee881c52c0db04262516309c0ecdea5873568c9b99af0e1696fb480','USER','ACTIVE'),
(9003,'booking_test_9003','0123456789abcdef0123456789abcdef','dd52b37acee881c52c0db04262516309c0ecdea5873568c9b99af0e1696fb480','USER','ACTIVE');
CREATE TABLE IF NOT EXISTS booking_fixture_credit (event_id VARCHAR(70) PRIMARY KEY,user_id BIGINT NOT NULL) ENGINE=InnoDB;
