# 共享研讨间预约平台

第 23 组课程项目。使用 Java 17、Maven、Tomcat 10.1 和 MySQL，最终实现 Web 应用。
## 三人分工和修改范围

Java 基础路径为 `src/main/java/com/sws/studyroom/`，页面基础路径为 `src/main/webapp/`。

| 负责人 | 负责功能 | Java 文件 | 页面 | 数据库 | 测试文档 | 开发分支 |
| --- | --- | --- | --- | --- | --- | --- |
| 沈玉婷 | 01–07，用户与房间管理 | `accountrooms/AccountRoomServlet.java` | `account-rooms/index.html` | `db/account-rooms.sql` | 2.1–2.3 | `feat/account-rooms` |
| 刘婧怡 | 08–12、19，预约、订单、支付退款、规则及后台信用管理 | `bookingrules/BookingRuleServlet.java` | `booking-rules/index.html` | `db/booking-rules.sql` | 2.4、2.7 | `feat/booking-rules` |
| 王鑫 | 13–18，签到、释放、信用与违约、评价、故障、申诉 | `usagefeedback/UsageFeedbackServlet.java` | `usage-feedback/index.html` | `db/usage-feedback.sql` | 2.5–2.6 | `feat/usage-feedback` |

各成员可以在自己的 Java 包和页面目录新增文件，把业务处理和数据库访问拆开。Servlet 的 TODO 处是开发入口。实现实际功能时改为继承 `HttpServlet`，按功能编写 `doGet`、`doPost` 等方法，或拆分为具体 Servlet。公共文件 `pom.xml`、`common/`、首页和 `db/init.sql` 由刘婧怡协调修改。

### 跨模块接口约定

直接使用 `common/SessionIdentity.java` 和 `common/ModuleContracts.java` 中的类型。业务实现分别放在分工表对应的 Java 包。浏览器 API 按系统设计说明书的路径，三个占位 Servlet 的路径供框架冒烟测试使用。

| 调用内容 | 负责人和用法 |
| --- | --- |
| 登录态 | 沈玉婷在认证成功后调用 `SessionIdentity.signIn`，登出调用 `signOut`。所有模块用 `current` 获取当前用户 ID 和角色。未登录返回 401，已登录但角色或资源权限不足返回 403。房管同时校验房间管理范围，订单、信用及申诉同时校验资源归属。 |
| 房间 | 沈玉婷实现 `RoomAccess.findRoom`。接口返回 `room_id`、`listing_status`、`maintenance_status`。只有 `LISTED` 且 `NORMAL` 才能提交新预约。某时段可用性还要查询占用。 |
| 订单与时段 | 刘婧怡实现 `BookingAccess`。王鑫签到及释放时调用其方法。签到进入使用中；结束、超时或取消等场景由预约模块校验状态和原因后删除对应的时段占用。退款处理中保留占用，退款成功后才释放。 |
| 信用 | 王鑫实现 `CreditAccess`。正常履约、违约、冲正和后台人工调整都通过 `applyChange` 记账。刘婧怡的后台调用该接口。事件号重试时保持不变，数据库唯一约束保证一次记账。 |

- JSON 字段用下划线命名。用户、房间、订单及规则快照分别用 `user_id`、`room_id`、`booking_id`、`rule_version_id`，对应 Java `long` 和 MySQL `BIGINT`。事件号使用 `event_id` 字符串。跨 Web 接口的 ID 使用十进制字符串，避免 JavaScript 数字精度丢失。
- 时间用 `start_time`、`end_time`，采用 Asia/Shanghai 本地业务时间、ISO 格式 `yyyy-MM-dd'T'HH:mm:ss`，数据库用 `DATETIME`。预约区间左闭右开。截止时间由服务端判定。
- 角色编码为 `USER`、`ROOM_ADMIN`、`SUPER_ADMIN`。房间上架为 `LISTED`，下架为 `UNLISTED`，正常为 `NORMAL`，维护为 `MAINTENANCE`。订单状态 `PENDING_PAYMENT`、`PENDING_CHECKIN`、`IN_USE`、`CANCELLED`、`FINISHED` 分别对应待支付、待签到、使用中、已取消、已结束。支付和退款结果分别记录在资金表。
| 事件 | 处理前 → 处理后 | 时段占用 |
| --- | --- | --- |
| 下单、支付成功、有效签到 | 创建待支付 → 待签到 → 使用中 | 创建后持续占用 |
| 支付前取消、支付超时或失败 | 待支付 → 已取消 | 立即释放 |
| 申请退款 | 待签到 → 已取消 | 保留占用 |
| 退款成功回调 | 已取消 → 已取消 | 仅释放尚保留的占用，重复回调不重复处理 |
| 超时未签到 | 待签到 → 已取消 | 释放并按规则记录违约 |
| 提前结束或到期 | 使用中 → 已结束 | 释放 |

- 下单和占位使用同一 JDBC 事务。`slot_lock` 按 `(room_id, slot_start)` 和 `(user_id, slot_start)` 分别设唯一约束。涉及多个模块的写操作由调用者开启事务并传入同一 `Connection`，被调用模块不提交、回滚或关闭它。信用流水和余额也在同一事务更新。签到、释放或信用事件重试时沿用同一事件号。
- 数据库表和外键以设计说明书第 7 章为准。`user`、`room` 归沈玉婷，`booking`、`slot_lock`、`payment`、`refund`、`rule_version`、`rule_tier` 归刘婧怡，`usage`、`credit_ledger`、`violation`、`review`、`fault`、`appeal` 归王鑫。模拟验证码、支付和门禁分别由沈玉婷、刘婧怡、王鑫实现。

## 本地启动

1. 安装 JDK 17、Maven 3.9、Tomcat 10.1。功能接数据库时安装 MySQL。
2. 在仓库根目录执行 `mvn clean verify`，产物为 `target/studyroom.war`。
3. 将 WAR 复制到 Tomcat 的 `webapps` 目录。Windows 前台启动用 `bin\catalina.bat run`，Linux 或 macOS 用 `bin/catalina.sh run`。重新部署时先停掉 Tomcat，再替换 WAR，避免旧的解包目录影响验证。
4. 浏览器访问 `http://localhost:8080/studyroom/`。静态页面调用接口时使用能保留 `/studyroom` 上下文的相对路径。

数据库接入时，用 MySQL 客户端在本地开发库执行 `source db/init.sql`，再执行已实现的模块 SQL。出现跨模块外键时先执行被引用表的建表脚本。从配置了以下环境变量的终端启动 Tomcat。

| 环境变量 | 示例或说明 |
| --- | --- |
| `SWS_DB_URL` | `jdbc:mysql://localhost:3306/studyroom` |
| `SWS_DB_USER` | 本地开发库账号 |
| `SWS_DB_PASSWORD` | 本地开发库密码，通过环境变量设置 |

## 怎么测试

框架冒烟验证在没有 MySQL 的情况下也可执行。

| 检查 | 预期 |
| --- | --- |
| 打开首页，点进三个模块后返回 | 首页和三个入口均可访问 |
| GET `/studyroom/api/health` | HTTP 200，响应为 `{"status":"ok"}` |
| GET `/studyroom/api/account-rooms/`、`/studyroom/api/booking-rules/`、`/studyroom/api/usage-feedback/` | 业务开发前返回 HTTP 501 与 `NOT_IMPLEMENTED` |

模块完工后重新运行 `mvn clean verify`，部署 WAR，用自己的测试账号和数据通过浏览器测试正常流程、异常输入和权限。测试文档填对应章节，记录输入和实际现象。首轮联调验证“登录 → 查房 → 下单 → 模拟支付 → 签到 → 释放”，同时确认同一房间同一时段不能被重复占用。

## 提交和合并

首次使用先克隆并进入仓库，组员需有仓库写权限才能推送分支。

```bash
git clone https://github.com/Lll666-ha/software_experiment_project_sws.git
cd software_experiment_project_sws
git switch main
git pull --ff-only origin main
git switch -c feat/account-rooms
```

第一位组员使用上例分支，其他人替换成分工表里的分支名。已有分支用 `git switch 分支名`。写完代码、通过本地验证后提交，以下以用户房间模块为例。

```bash
git status --short
git add src/main/java/com/sws/studyroom/accountrooms src/main/webapp/account-rooms db/account-rooms.sql
git diff --cached
git commit -m "实现用户与房间模块"
git fetch origin
git merge origin/main
git push -u origin HEAD
```

如果合并上游时有冲突，和对应负责人确认后解决，并重新测试。然后在 GitHub 开 Pull Request，base 选 `main`，compare 选自己的分支。PR 写明完成内容、数据库变更和测试结果，由另一名组员核对，组长合并。合并后其他成员执行 `git fetch origin`，再在自己的分支执行 `git merge origin/main` 同步。
