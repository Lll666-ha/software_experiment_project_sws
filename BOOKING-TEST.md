# 预约模块集成验证

按 FIELD-CONTRACT.md 顺序准备数据库，部署WAR后从“探索空间”下单，再到“我的预约”完成支付、退款、签到与结束。原 booking-rules/、usage-feedback/ 路径保留跳转至统一订单页面。

保留整点一小时粒度、每日时段额度、房间及用户双重占位唯一约束。CreditAccess、BookingAccess 均已接入，信用流水、未签到处理和到期释放由使用模块及定时扫描实现。课程支付、退款与门禁同步模拟。

自动验证由 GitHub Actions 在独立 MySQL8 / Tomcat10.1 中执行；43项业务检查与13项浏览器检查覆盖正常流程、权限、幂等、并发和关键时间边界。完整结果见 REVIEW-REPORT.md，手工专项测试表保留在各模块测试文档中。
