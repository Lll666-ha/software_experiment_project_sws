# 用户与房间模块本地运行说明

## 1. 软件要求

- JDK 17
- Maven 3.9+
- Tomcat 10.1
- MySQL 8.x

确认命令：

```powershell
java -version
mvn -version
mysql --version
```

## 2. 初始化数据库

登录 MySQL 后执行（路径按本机项目位置修改）：

```sql
source C:/path/software_experiment_project_sws/db/init.sql;
source C:/path/software_experiment_project_sws/db/account-rooms.sql;
source C:/path/software_experiment_project_sws/db/booking-rules.sql;
```

页面注册的账号默认都是普通用户。要测试房管功能，注册 `room_admin` 后执行：

```sql
USE studyroom;
UPDATE `user` SET role='ROOM_ADMIN' WHERE username='room_admin';
UPDATE room
SET manager_user_id=(SELECT user_id FROM `user` WHERE username='room_admin')
WHERE room_id IN (9101, 9102);
```

执行后退出页面并重新登录，使会话取得新角色。

## 3. 打包和部署

在设置数据库环境变量的同一个 PowerShell 中执行：

```powershell
$env:SWS_DB_URL = 'jdbc:mysql://localhost:3306/studyroom?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai'
$env:SWS_DB_USER = 'root'
$env:SWS_DB_PASSWORD = '替换成你的MySQL密码'

mvn clean verify
Copy-Item target\studyroom.war C:\path\apache-tomcat-10.1\webapps\studyroom.war -Force
& C:\path\apache-tomcat-10.1\bin\catalina.bat run
```

浏览器访问：

```text
http://localhost:8080/studyroom/account-rooms/
```

## 4. 自动冒烟测试

Tomcat 启动且数据库初始化后，另开 PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\account-rooms-smoke.ps1
```

脚本验证房间筛选、用户注册、登录会话、普通用户权限拦截、退出及再次登录。完整手工用例见 `ACCOUNT-ROOMS-TEST.md`。
