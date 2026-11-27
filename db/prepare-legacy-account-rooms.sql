-- Run after init.sql, BEFORE account-rooms.sql. Safe for both fresh and existing databases.
-- Earlier booking-only fixtures created minimal user/room tables. Keep their IDs and data.
USE studyroom;
DELIMITER $$
DROP PROCEDURE IF EXISTS sws_prepare_column$$
CREATE PROCEDURE sws_prepare_column(IN tab_name VARCHAR(64),IN col_name VARCHAR(64),IN definition TEXT)
BEGIN
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=tab_name)
 AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name) THEN
  SET @ddl=CONCAT('ALTER TABLE `',tab_name,'` ADD COLUMN `',col_name,'` ',definition);
  PREPARE sws_stmt FROM @ddl; EXECUTE sws_stmt; DEALLOCATE PREPARE sws_stmt;
 END IF;
END$$
DROP PROCEDURE IF EXISTS sws_prepare_accounts$$
CREATE PROCEDURE sws_prepare_accounts()
BEGIN
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='user') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='user' AND column_name='user_id' AND extra LIKE '%auto_increment%') THEN
   ALTER TABLE `user` MODIFY user_id BIGINT NOT NULL AUTO_INCREMENT;
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user' AND index_name='uk_user_username') THEN
   ALTER TABLE `user` ADD UNIQUE KEY uk_user_username(username);
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='user' AND index_name='uk_user_phone') THEN
   ALTER TABLE `user` ADD UNIQUE KEY uk_user_phone(phone);
  END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='room') THEN
  IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='room' AND column_name='room_id' AND extra LIKE '%auto_increment%') THEN
   ALTER TABLE room MODIFY room_id BIGINT NOT NULL AUTO_INCREMENT;
  END IF;
  UPDATE room SET room_name=CONCAT('研讨间 ',room_id) WHERE room_name IS NULL OR room_name='';
  UPDATE room SET location='待完善位置' WHERE location IS NULL OR location='';
  IF NOT EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='room' AND index_name='uk_room_name_location') THEN
   ALTER TABLE room ADD UNIQUE KEY uk_room_name_location(room_name,location);
  END IF;
 END IF;
END$$
DELIMITER ;
CALL sws_prepare_column('user','username','VARCHAR(32) NULL');
CALL sws_prepare_column('user','phone','VARCHAR(20) NULL');
CALL sws_prepare_column('user','password_salt','CHAR(32) NULL');
CALL sws_prepare_column('user','password_hash','CHAR(64) NULL');
CALL sws_prepare_column('user','role',"VARCHAR(24) NOT NULL DEFAULT 'USER'");
CALL sws_prepare_column('user','account_status',"VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'");
CALL sws_prepare_column('user','credit_score','INT NOT NULL DEFAULT 300');
CALL sws_prepare_column('user','credit_suspended_until','DATETIME NULL');
CALL sws_prepare_column('user','created_at','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL sws_prepare_column('user','updated_at','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
CALL sws_prepare_column('room','room_name','VARCHAR(80) NULL');
CALL sws_prepare_column('room','location','VARCHAR(160) NULL');
CALL sws_prepare_column('room','capacity','INT NOT NULL DEFAULT 4');
CALL sws_prepare_column('room','opening_hours',"VARCHAR(11) NOT NULL DEFAULT '08:00-22:00'");
CALL sws_prepare_column('room','listing_status',"VARCHAR(24) NOT NULL DEFAULT 'UNLISTED'");
CALL sws_prepare_column('room','maintenance_status',"VARCHAR(24) NOT NULL DEFAULT 'NORMAL'");
CALL sws_prepare_column('room','manager_user_id','BIGINT NULL');
CALL sws_prepare_column('room','maintenance_before_fault',"VARCHAR(24) NOT NULL DEFAULT 'NORMAL'");
CALL sws_prepare_column('room','fault_maintenance_active','TINYINT NOT NULL DEFAULT 0');
CALL sws_prepare_column('room','created_at','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL sws_prepare_column('room','updated_at','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP');
CALL sws_prepare_accounts();
DROP PROCEDURE sws_prepare_column;
DROP PROCEDURE sws_prepare_accounts;
-- Legacy fixture users without usernames/passwords are retained as historical records.
-- Register real accounts through the application; do not turn arbitrary fixture IDs into login credentials.
