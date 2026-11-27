# 共享研讨间预约平台

Java 17、Maven、Tomcat 10.1、MySQL 8，三人课程项目。

## 运行

1. 按 `FIELD-CONTRACT.md` 的顺序建库或迁移历史库，再执行 `db/notifications.sql` 、`db/room-details.sql` 和 `db/lifecycle-details.sql`，启用站内信、房间详情、账号验证和退款状态。需要演示房间时，可额外执行 `db/add-demo-rooms.sql`。
2. 设置 `SWS_DB_URL`、`SWS_DB_USER`、`SWS_DB_PASSWORD`。示例URL：`jdbc:mysql://localhost:3306/studyroom?serverTimezone=Asia/Shanghai`。
3. `mvn clean verify`，将 `target/studyroom.war` 部署至 Tomcat 10.1。
4. 访问 `/studyroom/`。账号与房间、预约与规则、使用与反馈保留独立入口。

新注册用户为 USER，初始信用300。课程演示房管 `room_admin / Admin@123456` 管理9101、9102。超级管理员由数据库管理者为已注册账号分配 SUPER_ADMIN 角色，不开放自行提权接口。支付、退款、验证码和门禁按课程要求模拟。

## 文档与验证

- `FIELD-CONTRACT.md`：字段、枚举、接口、规则、迁移顺序及历史兼容约定。
- `REVIEW-REPORT.md`：发现的问题、修复范围和验证结果。
- GitHub Actions `Verify integrated studyroom` 使用独立 MySQL 8 服务，执行建表、重复迁移、编译打包和真实数据库集成检查。
- `IntegrationChecks` 为独立集成检查入口，不会在普通 `mvn verify` 时连接你的数据库。仅在明确设置 `SWS_INTEGRATION_TEST=1` 的隔离库运行。

三模块通过 `common/SessionIdentity`、`ModuleContracts` 和同一 JDBC 事务协作。业务流程为注册登录→查房→下单→模拟支付→签到→结束→评价；故障与申诉由相应房管处理，预约规则和人工信用调整由超级管理员管理。

注册和找回密码页面可生成课程模拟验证码。预约支付、退款、签到可选择模拟结果。退款处理期间保留时段，模拟渠道约一分钟后返回结果。退款失败由超级管理员在「退款核对」中处理。
