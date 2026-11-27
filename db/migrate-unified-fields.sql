-- Existing database upgrade. Back up first; do not re-import old fixture rows.
-- Run once with the schema-owning account after the three module CREATE scripts.
USE studyroom;
DELIMITER $$
DROP PROCEDURE IF EXISTS sws_add_column$$
CREATE PROCEDURE sws_add_column(IN tab_name VARCHAR(64),IN col_name VARCHAR(64),IN definition TEXT)
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=col_name) THEN
  SET @ddl=CONCAT('ALTER TABLE `',tab_name,'` ADD COLUMN `',col_name,'` ',definition);
  PREPARE sws_stmt FROM @ddl; EXECUTE sws_stmt; DEALLOCATE PREPARE sws_stmt;
 END IF;
END$$
DROP PROCEDURE IF EXISTS sws_rename_column$$
CREATE PROCEDURE sws_rename_column(IN tab_name VARCHAR(64),IN old_name VARCHAR(64),IN new_name VARCHAR(64))
BEGIN
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=old_name)
 AND NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab_name AND column_name=new_name) THEN
  SET @ddl=CONCAT('ALTER TABLE `',tab_name,'` RENAME COLUMN `',old_name,'` TO `',new_name,'`');
  PREPARE sws_stmt FROM @ddl; EXECUTE sws_stmt; DEALLOCATE PREPARE sws_stmt;
 END IF;
END$$
DELIMITER ;
CALL sws_rename_column('credit_ledger','event_no','event_id');
CALL sws_rename_column('credit_ledger','create_time','created_at');
CALL sws_rename_column('violation','create_time','created_at');
CALL sws_rename_column('review','create_time','created_at');
CALL sws_rename_column('fault','create_time','created_at');
CALL sws_rename_column('fault','finish_time','finished_at');
CALL sws_rename_column('appeal','create_time','created_at');
CALL sws_rename_column('appeal','handle_time','handled_at');
CALL sws_add_column('booking','created_at','DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP');
CALL sws_add_column('room','maintenance_before_fault',"VARCHAR(24) NOT NULL DEFAULT 'NORMAL'");
CALL sws_add_column('room','fault_maintenance_active','TINYINT NOT NULL DEFAULT 0');
CALL sws_add_column('rule_version','checkin_early_minutes','INT NOT NULL DEFAULT 15');
CALL sws_add_column('rule_version','checkin_late_minutes','INT NOT NULL DEFAULT 15');
CALL sws_add_column('rule_version','timeout_deduct','INT NOT NULL DEFAULT 10');
CALL sws_add_column('rule_version','early_release_deduct','INT NOT NULL DEFAULT 0');
CALL sws_add_column('rule_version','credit_suspend_days','INT NOT NULL DEFAULT 3');
CALL sws_add_column('rule_version','review_window_hours','INT NOT NULL DEFAULT 24');
CALL sws_add_column('usage','gate_request_id','VARCHAR(128) NULL');
CALL sws_add_column('usage','door_attempt_count','INT NOT NULL DEFAULT 0');
CALL sws_add_column('credit_ledger','requested_delta','INT NULL');
CALL sws_add_column('credit_ledger','score_after','INT NULL');
CALL sws_add_column('fault','handled_by','BIGINT NULL');
CALL sws_add_column('fault','handled_at','DATETIME NULL');
CALL sws_add_column('fault','admin_opinion','TEXT NULL');
CALL sws_add_column('appeal','handled_by','BIGINT NULL');
ALTER TABLE `user` ALTER COLUMN credit_score SET DEFAULT 300;
UPDATE `user` SET account_status='ACTIVE' WHERE account_status IN ('NORMAL','正常');
UPDATE `user` SET account_status='DISABLED' WHERE account_status IN ('FROZEN','冻结');
UPDATE room SET listing_status='LISTED' WHERE listing_status='上架';
UPDATE room SET listing_status='UNLISTED' WHERE listing_status='下架';
UPDATE room SET maintenance_status='NORMAL' WHERE maintenance_status='正常';
UPDATE room SET maintenance_status='MAINTENANCE' WHERE maintenance_status='维护';
UPDATE `usage` SET door_open_result='SUCCESS' WHERE door_open_result='成功';
UPDATE `usage` SET door_open_result='FAILED' WHERE door_open_result='失败';
UPDATE fault SET fault_status='PENDING' WHERE fault_status='待受理';
UPDATE fault SET fault_status='REPAIRING' WHERE fault_status IN ('维修中','处理中');
UPDATE fault SET fault_status='RESOLVED' WHERE fault_status IN ('已修复完成','已完成');
UPDATE appeal SET appeal_status='PENDING' WHERE appeal_status='待审核';
UPDATE appeal SET appeal_status='APPROVED' WHERE appeal_status='审核通过';
UPDATE appeal SET appeal_status='REJECTED' WHERE appeal_status IN ('申诉驳回','审核驳回');
UPDATE violation SET violation_type='NO_SHOW' WHERE violation_type='超时未签到';
-- Historical scores/ledger values are preserved; unknown historical requested_delta/score_after stay NULL.
DROP PROCEDURE sws_add_column;
DROP PROCEDURE sws_rename_column;
