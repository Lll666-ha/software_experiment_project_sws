# 预约模块启动与验证

先执行 db/init.sql，再执行 db/booking-rules.sql。预约依赖已合并的登录、user 和 room。房间开放时间用 HH:mm-HH:mm，例如 08:00-22:00。拷贝打包后的 WAR 到 Tomcat 后，从首页进入预约与规则。用普通用户测试预约，超级管理员测试规则和信用。页面显示的时间为北京时间，课程实现采用整点一小时时段，模拟支付和退款在请求内完成。

信用调整依赖信用模块在 ServletContext 中以 CreditAccess.class.getName() 注册服务。签到模块可以按 BookingAccess.class.getName() 取得预约服务，在自己的事务中调用。未支付过期订单在用户刷新订单列表或尝试支付时处理。真实渠道回调、后台定时扫描、违规和信用流水展示尚待联调或补充。
