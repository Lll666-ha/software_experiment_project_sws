package com.sws.studyroom.bookingrules;

import com.sws.studyroom.common.PendingServlet;
import jakarta.servlet.annotation.WebServlet;

@WebServlet("/api/booking-rules/*")
public class BookingRuleServlet extends PendingServlet {
    // TODO(刘婧怡)：实现时段校验、原子下单与占位、模拟支付退款、取消、规则配置及后台信用管理。
    // 实现后改为继承 HttpServlet，并按约定为具体路由实现 HTTP 方法。
}
