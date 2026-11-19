# 共享研讨间预约平台

第 23 组课程项目。使用 Java 17、Maven、Tomcat 10.1 和 MySQl,最终实现Web 应用。
## 三人分工和修改范围

Java 基础路径为 `src/main/java/com/sws/studyroom/`，页面基础路径为 `src/main/webapp/`。

| 负责人 | 负责功能 | Java 文件 | 页面 | 数据库 | 测试文档 | 开发分支 |
| --- | --- | --- | --- | --- | --- | --- |
| 沈玉婷 | 01–07，用户与房间管理 | `accountrooms/AccountRoomServlet.java` | `account-rooms/index.html` | `db/account-rooms.sql` | 2.1–2.3 | `feat/account-rooms` |
| 刘婧怡 | 08–12、19，预约、订单、支付退款、规则及后台信用管理 | `bookingrules/BookingRuleServlet.java` | `booking-rules/index.html` | `db/booking-rules.sql` | 2.4、2.7 | `feat/booking-rules` |
| 王鑫 | 13–18，签到、释放、信用与违约、评价、故障、申诉 | `usagefeedback/UsageFeedbackServlet.java` | `usage-feedback/index.html` | `db/usage-feedback.sql` | 2.5–2.6 | `feat/usage-feedback` |

各成员可以在自己的 Java 包和页面目录新增文件，把业务处理和数据库访问拆开。Servlet 的 TODO 处是开发入口。实现实际功能时改为继承 `HttpServlet`，按功能编写 `doGet`、`doPost` 等方法，或拆分为具体 Servlet。公共文件 `pom.xml`、`common/`、首页和 `db/init.sql` 由刘婧怡协调修改。

### 开工前需要对齐的接口

- 沈玉婷定义登录后的用户 ID、角色、房间 ID 和房间状态的接口；刘婧怡和王鑫使用同一套字段。服务端依据登录态判断当前用户和角色。
- 刘婧怡与王鑫确认订单状态、订单 ID、预约起止时间和释放入口。订单与时段锁由预约模块统一维护，取消或签到相关操作走约定入口，防止重复释放。
- 王鑫负责业务信用变更，刘婧怡负责后台查询与管理入口。两处共用同一信用流水与记账规则。
- 下单与占位在同一事务中完成。跨模块表名、字段、枚举和外键先按设计文档确认，再提交 SQL。支付、验证码和门禁按课程要求预留模拟入口。

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
