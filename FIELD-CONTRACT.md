# 字段统一与模块联调约定

日期：2026-11-27。适用于 Java 17 / Tomcat 10.1 / MySQL 8 的共享研讨间预约平台。

## 统一原则

数据库、表单参数和 JSON 使用 `snake_case`；Java 方法及记录成员使用 `camelCase`。主键和外键为 MySQL `BIGINT` / Java `long`；JSON 中所有 `*_id` 均输出十进制字符串，前端不得用 `Number` 转换。`event_id` 是字符串业务事件号。金额使用 `DECIMAL(10,2)` / `BigDecimal`，由服务端计算。

业务时间为 Asia/Shanghai；接口时间用 `yyyy-MM-ddTHH:mm:ss`，数据库用 `DATETIME`。创建时间统一为 `created_at`；实际使用结束为 `actual_end_time`，故障完成为 `finished_at`，管理处理为 `handled_at`。历史真实记录保留。

## 名称与枚举对应

| 原称 / 设计文档称呼 | 代码统一名称 | 说明 |
| --- | --- | --- |
| order_id / orderId / 订单ID | booking_id / bookingId | 统一关联 booking |
| event_no / eventNo | event_id / eventId | 稳定业务来源，不因重试变化 |
| create_time / createdAt | created_at / createdAt | 服务端生成 |
| handle_time | handled_at | 处理时间 |
| finish_time | finished_at | 故障完成时间 |
| 正常 / NORMAL（账号） | ACTIVE | 与登录、预约、冻结判断一致 |
| 冻结 / FROZEN | DISABLED | 不修改信用分或信用暂停期限 |
| 整洁度 / cleanScore | score_1 | 整数1—5 |
| 设备完好度 / deviceScore | score_2 | 整数1—5 |
| content / 评价正文 | comment | 最长500字 |
| gate状态成功 / 失败 | SUCCESS / FAILED | 与订单状态分别记录 |

| 业务 | 合法值 |
| --- | --- |
| role | USER、ROOM_ADMIN、SUPER_ADMIN |
| account_status | ACTIVE、DISABLED |
| listing_status | LISTED、UNLISTED |
| maintenance_status | NORMAL、MAINTENANCE |
| booking_status | PENDING_PAYMENT、PENDING_CHECKIN、IN_USE、CANCELLED、FINISHED |
| fault_status | PENDING、REPAIRING、RESOLVED |
| appeal_status | PENDING、APPROVED、REJECTED |
| violation_type（超时未签到） | NO_SHOW |

页面显示中文，向服务端提交英文枚举。超时未签到保留订单 CANCELLED 状态，具体原因与扣分在 violation / credit_ledger 中保留，沿用原订单状态集合。

## 业务规则与事务

初始信用300分，范围0—300；签到窗口默认开始前15分钟至开始后15分钟，包含两端；严格超过右端才登记未签到。评价限实际结束后24小时内，包含截止时刻，一单一次。0分产生3—7天预约暂停，人工解冻及加分不清除暂停。

保持原预约核心：整点时段、未来7天、默认每天最多2个时段、每小时10元、24小时内按订单快照中的退款比例计算。下单和全部占位同一事务，用户与房间的时段唯一约束分别防冲突。已有订单继续引用原规则版本。课程支付、退款和门禁使用同步模拟，不调用真实资金或设备接口。

规则补充字段默认：`checkin_early_minutes=15`、`checkin_late_minutes=15`、`timeout_deduct=10`、`early_release_deduct=0`、`credit_suspend_days=3`、`review_window_hours=24`。10分延续原使用模块示例扣分；默认提前释放不扣分。管理员发布新版本可修改，旧订单不随新规则变化。

所有跨模块变更由调用方控制同一 `Connection`，服务不自行提交。锁顺序为用户→房间→订单→签到/申诉→信用流水；房间独立报障处理为房间→故障。重复签到、结束、超时扫描及申诉复核不重复变更。信用事件由服务端业务来源生成：`timeout:booking_id`、`early:booking_id`、`appeal:violation_id`；人工调整为 `admin:操作号`，同号不同用户或内容拒绝。

## 接口约定

共用 `SessionIdentity` 登录会话和 `csrf` 字段。账号模块 `GET /api/account-rooms/token` 返回 `{ "csrf": "..." }`。POST 表单带 `csrf`，使用与反馈 JSON 请求可用 `X-CSRF-Token`。未登录401、越权403、格式错误400、状态或重复冲突409、数据库失败500。

| 模块 | 路径（省略 /api/） | 主要字段 |
| --- | --- | --- |
| 账号房间 | account-rooms/register、login、logout | username、phone、password、csrf |
| 密码重置 | account-rooms/password-reset/request、confirm | username、phone；code、new_password |
| 房间 | account-rooms/rooms、admin/rooms、admin/rooms/save、admin/rooms/status | room_id、room_name、location、capacity、opening_hours、manager_user_id、listing_status、maintenance_status |
| 预约 | booking-rules/orders | room_id、start_time、end_time；GET为本人订单 |
| 支付退款 | booking-rules/pay、cancel、quote、refund | booking_id |
| 结构化查询 | booking-rules/order-data、rule-data | GET，返回订单数组或当前规则JSON |
| 规则管理 | booking-rules/admin/rules | advance_days、daily_slots、hourly_price、refund_percent及规则补充字段；rule_version_id用于版本复核 |
| 账号冻结 | booking-rules/admin/freeze | user_id、action=freeze/unfreeze |
| 信用调整 | booking-rules/admin/credit | user_id、delta、reason、event_id |
| 签到、门禁、结束 | usage-feedback/checkin、door/retry、finish | booking_id；业务事件由服务端生成 |
| 评价 | usage-feedback/review | booking_id、score_1、score_2、comment |
| 报障 | usage-feedback/fault | room_id、fault_desc |
| 申诉 | usage-feedback/appeal | booking_id、violation_id、appeal_reason |
| 个人记录 | usage-feedback/me/credit、me/faults、me/appeals | GET，身份取会话 |
| 管理查询 | usage-feedback/admin/faults、admin/appeals | GET，按房管管理范围返回 |
| 管理处理 | usage-feedback/admin/fault、admin/appeal | fault_id、fault_status、admin_opinion；appeal_id、decision、admin_opinion |
| 房间评价 | usage-feedback/reviews | room_id、page（每页20条） |

账号模块JSON使用 `message` 和对应业务字段；使用与反馈使用 `{message,data}`。预约旧接口保留文本响应，新增 order-data / rule-data 提供结构化数据。前端按相应响应格式处理，不解析中文文本推导订单状态。

## 数据库字段清单

下列清单依据当前建表脚本生成。历史库须先执行迁移，避免仅部署WAR导致列名不匹配。

### user

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `user_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `username` | `VARCHAR(32)` | NOT NULL |
| `phone` | `VARCHAR(20)` | NULL |
| `password_salt` | `CHAR(32)` | NOT NULL |
| `password_hash` | `CHAR(64)` | NOT NULL |
| `role` | `VARCHAR(24)` | NOT NULL DEFAULT 'USER' |
| `account_status` | `VARCHAR(24)` | NOT NULL DEFAULT 'ACTIVE' |
| `credit_score` | `INT` | NOT NULL DEFAULT 300 |
| `credit_suspended_until` | `DATETIME` | NULL |
| `created_at` | `DATETIME` | NOT NULL DEFAULT CURRENT_TIMESTAMP |
| `updated_at` | `DATETIME` | NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP |

### room

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `room_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `room_name` | `VARCHAR(80)` | NOT NULL |
| `location` | `VARCHAR(160)` | NOT NULL |
| `capacity` | `INT` | NOT NULL |
| `opening_hours` | `VARCHAR(11)` | NOT NULL DEFAULT '08:00-22:00' |
| `listing_status` | `VARCHAR(24)` | NOT NULL DEFAULT 'UNLISTED' |
| `maintenance_status` | `VARCHAR(24)` | NOT NULL DEFAULT 'NORMAL' |
| `manager_user_id` | `BIGINT` | NULL |
| `maintenance_before_fault` | `VARCHAR(24)` | NOT NULL DEFAULT 'NORMAL' |
| `fault_maintenance_active` | `TINYINT` | NOT NULL DEFAULT 0 |
| `created_at` | `DATETIME` | NOT NULL DEFAULT CURRENT_TIMESTAMP |
| `updated_at` | `DATETIME` | NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP |

### rule_version

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `rule_version_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `advance_days` | `INT` | NOT NULL |
| `daily_slots` | `INT` | NOT NULL |
| `hourly_price` | `DECIMAL(10,2)` | NOT NULL |
| `refund_percent` | `INT` | NOT NULL |
| `checkin_early_minutes` | `INT` | NOT NULL DEFAULT 15 |
| `checkin_late_minutes` | `INT` | NOT NULL DEFAULT 15 |
| `timeout_deduct` | `INT` | NOT NULL DEFAULT 10 |
| `early_release_deduct` | `INT` | NOT NULL DEFAULT 0 |
| `credit_suspend_days` | `INT` | NOT NULL DEFAULT 3 |
| `review_window_hours` | `INT` | NOT NULL DEFAULT 24 |

### booking

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `booking_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `user_id` | `BIGINT` | NOT NULL |
| `room_id` | `BIGINT` | NOT NULL |
| `start_time` | `DATETIME` | NOT NULL |
| `end_time` | `DATETIME` | NOT NULL |
| `total_amount` | `DECIMAL(10,2)` | NOT NULL |
| `booking_status` | `VARCHAR(24)` | NOT NULL |
| `rule_version_id` | `BIGINT` | NOT NULL |
| `pay_deadline` | `DATETIME` | NOT NULL |
| `created_at` | `DATETIME` | NOT NULL DEFAULT CURRENT_TIMESTAMP |

### slot_lock

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `occupancy_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `booking_id` | `BIGINT` | NOT NULL |
| `user_id` | `BIGINT` | NOT NULL |
| `room_id` | `BIGINT` | NOT NULL |
| `slot_start` | `DATETIME` | NOT NULL |
| `slot_end` | `DATETIME` | NOT NULL |

### payment

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `booking_id` | `BIGINT` | PRIMARY KEY |
| `pay_amount` | `DECIMAL(10,2)` | NOT NULL |
| `paid_at` | `DATETIME` | NOT NULL |

### refund

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `booking_id` | `BIGINT` | PRIMARY KEY |
| `refund_amount` | `DECIMAL(10,2)` | NOT NULL |
| `refunded_at` | `DATETIME` | NOT NULL |

### usage

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `usage_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `booking_id` | `BIGINT` | NOT NULL |
| `gate_request_id` | `VARCHAR(128)` | NULL |
| `door_attempt_count` | `INT` | NOT NULL DEFAULT 0 |
| `sign_in_time` | `DATETIME` | NULL |
| `door_open_result` | `VARCHAR(32)` | NOT NULL |
| `actual_end_time` | `DATETIME` | NULL |

### credit_ledger

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `ledger_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `user_id` | `BIGINT` | NOT NULL |
| `violation_id` | `BIGINT` | NULL |
| `appeal_id` | `BIGINT` | NULL |
| `requested_delta` | `INT` | NOT NULL |
| `score_after` | `INT` | NOT NULL |
| `delta_score` | `INT` | NOT NULL |
| `reason` | `TEXT` | NOT NULL |
| `created_at` | `DATETIME` | NOT NULL |
| `event_id` | `VARCHAR(128)` | NOT NULL |

### violation

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `violation_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `booking_id` | `BIGINT` | NOT NULL |
| `user_id` | `BIGINT` | NOT NULL |
| `violation_type` | `VARCHAR(64)` | NOT NULL |
| `credit_deduct` | `INT` | NOT NULL |
| `is_revoked` | `TINYINT` | NOT NULL DEFAULT 0 |
| `created_at` | `DATETIME` | NOT NULL |

### review

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `review_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `booking_id` | `BIGINT` | NOT NULL |
| `user_id` | `BIGINT` | NOT NULL |
| `score_1` | `TINYINT` | NOT NULL |
| `score_2` | `TINYINT` | NOT NULL |
| `comment` | `TEXT` | NULL |
| `created_at` | `DATETIME` | NOT NULL |

### fault

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `fault_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `room_id` | `BIGINT` | NOT NULL |
| `user_id` | `BIGINT` | NOT NULL |
| `fault_desc` | `TEXT` | NOT NULL |
| `fault_status` | `VARCHAR(32)` | NOT NULL |
| `created_at` | `DATETIME` | NOT NULL |
| `handled_by` | `BIGINT` | NULL |
| `handled_at` | `DATETIME` | NULL |
| `admin_opinion` | `TEXT` | NULL |
| `finished_at` | `DATETIME` | NULL |

### appeal

| 字段 | SQL类型 | 约束/默认值 |
| --- | --- | --- |
| `appeal_id` | `BIGINT` | AUTO_INCREMENT PRIMARY KEY |
| `booking_id` | `BIGINT` | NOT NULL |
| `user_id` | `BIGINT` | NOT NULL |
| `violation_id` | `BIGINT` | NOT NULL |
| `appeal_reason` | `TEXT` | NOT NULL |
| `admin_opinion` | `TEXT` | NULL |
| `appeal_status` | `VARCHAR(32)` | NOT NULL |
| `created_at` | `DATETIME` | NOT NULL |
| `handled_by` | `BIGINT` | NULL |
| `handled_at` | `DATETIME` | NULL |

## 部署与历史库迁移

新建数据库按顺序执行：`db/init.sql` → `db/account-rooms.sql` → `db/booking-rules.sql` → `db/usage-feedback.sql` → `db/migrate-unified-fields.sql`。历史数据库先备份，再执行迁移脚本；不要重新导入旧演示数据。迁移可重复执行，改列名、补列并统一可识别状态，保留现有信用分和业务记录；只把新用户默认分改为300，不将已有100分账户直接改成300分。

历史信用流水缺少的 requested_delta / score_after 保留NULL，不能根据当前信用分伪造历史快照。旧 usage 示例数据可能关联不存在的订单，须人工核对来源；新建库不再插入这些孤立记录。迁移脚本不删除任何历史记录。

`local-test/booking-login.jsp` 仅保留作历史测试材料，不放进 webapp、不打入WAR；正式登录通过账号模块。
