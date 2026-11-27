# 账号与房间模块运行

使用JDK17、Maven、Tomcat10.1、MySQL8，运行环境和部署方式见README.md。

数据库按 FIELD-CONTRACT.md 的完整顺序执行。早期预约测试库务必先运行 prepare-legacy-account-rooms.sql，再运行正式模块脚本与统一迁移，避免旧user/room缺少账号或房间详情字段。

新建库已有课程演示房管 room_admin / Admin@123456，管理9101、9102，无须重复注册该账号。新注册用户为USER；超级管理员由数据库管理者给已注册账号分配SUPER_ADMIN角色。

统一入口为 /studyroom/，旧 /studyroom/account-rooms/ 路径保留跳转。房间筛选在“探索空间”，房间新增、编辑、上下架和维护在“管理工作台”。

scripts/account-rooms-smoke.ps1 保留原API冒烟测试；完整自动集成验证见 .github/workflows/verify.yml。ACCOUNT-ROOMS-TEST.md 为原人工测试记录表，按实际执行填写，不将自动检查结果代填为人工执行结果。
