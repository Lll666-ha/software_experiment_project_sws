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

-- -------------------- 测试数据，INSERT IGNORE：重复执行脚本不会主键冲突 --------------------
-- 签到记录（用来测试提前结束）
INSERT IGNORE INTO `usage`(usage_id, booking_id, sign_in_time, door_open_result, actual_end_time)
VALUES (1, 1, '2026-11-26 14:05:00', '成功', NULL);

-- 违规记录（用来测试申诉）
INSERT IGNORE INTO violation(violation_id, booking_id, user_id, violation_type, credit_deduct, is_revoked, create_time)
VALUES (1, 1, 1, '超时未签到', 10, 0, '2026-11-26 14:10:00');

-- 评价记录（用来测试重复提交评价报错）
INSERT IGNORE INTO review(review_id, booking_id, user_id, score_1, score_2, comment, create_time)
VALUES (1, 1, 1, 4, 4, '设备正常，环境整洁', '2026-11-26 16:30:00');

-- 故障记录（查看上报后的结果）
INSERT IGNORE INTO fault(fault_id, room_id, user_id, fault_desc, fault_status, create_time)
VALUES (1, 1, 1, '投影仪画面模糊', '待受理', '2026-11-26 15:00:00');
