package com.sws.studyroom.usagefeedback;

import com.sws.studyroom.common.PendingServlet;
import jakarta.servlet.annotation.WebServlet;

@WebServlet("/api/usage-feedback/*")
public class UsageFeedbackServlet extends PendingServlet {
    // TODO(王鑫)：实现签到、模拟门禁、释放、信用记账、评价、故障及申诉。
    // 实现后改为继承 HttpServlet，并按约定为具体路由实现 HTTP 方法。
}
