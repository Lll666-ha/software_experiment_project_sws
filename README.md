# 共享研讨间预约平台

以下步骤适用于已部署的 Windows 演示环境。

## 打开网页

在部署电脑上打开 [共享研讨间预约平台](http://localhost:8080/studyroom/)。

如果 Tomcat 尚未启动，先确认 MySQL 服务已运行，再打开 PowerShell 执行下面的命令。密码提示处填演示环境中 `studyroom_test` 的 MySQL 密码。

```powershell
$env:JAVA_HOME = 'E:\DevTools\jdk-17.0.21.1+2'
$env:SWS_DB_URL = 'jdbc:mysql://localhost:3306/studyroom?serverTimezone=Asia/Shanghai'
$env:SWS_DB_USER = 'studyroom_test'
$dbPassword = Read-Host '输入 studyroom_test 的 MySQL 密码' -AsSecureString
$env:SWS_DB_PASSWORD = [System.Net.NetworkCredential]::new('', $dbPassword).Password
& 'E:\DevTools\apache-tomcat-10.1.62\bin\catalina.bat' run
```

看到「服务器启动」后，保持 PowerShell 窗口开启，再打开网页。

## 普通用户

点击右上角「登录 / 注册」。登录后进入「探索空间」，按人数、地点或设备筛选房间，点击「房间详情」查看设备和使用规则。点击「预约空间」选择日期与时段，在「我的预约」里完成模拟支付、签到或结束使用。

## 房间管理员

用课程演示账号 `room_admin` 登录，密码向项目组获取。进入 [管理工作台](http://localhost:8080/studyroom/#admin)，在「房间管理」中选择房间并点击「编辑」，即可修改设备和使用规则。保存后可回到「探索空间」查看。报障与申诉分别在「故障处理」和「申诉复核」中查看。

## 首次部署

使用 Java 17、Maven、Tomcat 10.1 和 MySQL 8。按 [建库顺序](FIELD-CONTRACT.md) 初始化或迁移数据库，然后执行 `db/notifications.sql` 和 `db/lifecycle-details.sql`。需要扩展演示房间时，先执行 `db/add-demo-rooms.sql`，最后执行 `db/room-details.sql`。运行 `mvn clean verify`，将 `target/studyroom.war` 放入 Tomcat 的 `webapps` 目录，再按上面的步骤启动。
