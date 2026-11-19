package com.sws.studyroom.accountrooms;

import com.sws.studyroom.common.PendingServlet;
import jakarta.servlet.annotation.WebServlet;

@WebServlet("/api/account-rooms/*")
public class AccountRoomServlet extends PendingServlet {
    // TODO(沈玉婷)：实现注册、登录、密码重置、房间展示与筛选、房间新增编辑和上下架维护。
    // 使用 SessionIdentity 写入和读取登录态，实现 ModuleContracts.RoomAccess。
    // 实现后改为继承 HttpServlet，并按约定为具体路由实现 HTTP 方法。
}
