USE studyroom;
-- usage‑feedback模块：usage、credit_ledger、violation、review、fault、appeal

-- 签到使用记录表
CREATE TABLE IF NOT EXISTS `usage` (
                                       usage_id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '签到记录主键',
                                       booking_id BIGINT NOT NULL COMMENT '关联预约订单',
                                       gate_request_id VARCHAR(128) NULL,
                                       door_attempt_count INT NOT NULL DEFAULT 0,
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
                                             requested_delta INT NOT NULL,
                                             score_after INT NOT NULL,
                                             delta_score INT NOT NULL COMMENT '正数加分、负数扣分',
                                             reason TEXT NOT NULL,
                                             created_at DATETIME NOT NULL,
                                             event_id VARCHAR(128) NOT NULL COMMENT '幂等事件编号',
                                             UNIQUE KEY uk_ledger_eventno(event_id),
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
                                         created_at DATETIME NOT NULL,
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
                                      created_at DATETIME NOT NULL,
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
                                     created_at DATETIME NOT NULL,
                                     handled_by BIGINT NULL,
                                     handled_at DATETIME NULL,
                                     admin_opinion TEXT NULL,
                                     finished_at DATETIME NULL,
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
                                      created_at DATETIME NOT NULL,
                                      handled_by BIGINT NULL,
                                      handled_at DATETIME NULL,
                                      INDEX idx_violation(violation_id),
                                      INDEX idx_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申诉记录表';

