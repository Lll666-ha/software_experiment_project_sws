package com.sws.studyroom.common;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import static com.sws.studyroom.common.Sql.*;

/** Recheck the account and role so an old session cannot bypass a freeze. */
@WebFilter("/api/*")
public class ActiveAccountFilter implements Filter {
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
        var req=(HttpServletRequest)request;var res=(HttpServletResponse)response;
        var actor=SessionIdentity.current(req).orElse(null);
        if(actor!=null) {
            try(var c=Database.open()) {
                var row=one(c,"SELECT role,account_status FROM `user` WHERE user_id=?",actor.userId());
                if(row==null||!"ACTIVE".equals(row.get("account_status"))) {
                    SessionIdentity.signOut(req);res.setStatus(403);res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().print(Json.encode(java.util.Map.of("message","账号已停用，请联系管理员")));return;
                }
                SessionIdentity.refresh(req,new SessionIdentity(actor.userId(),SessionIdentity.Role.valueOf(row.get("role").toString())));
            } catch(SQLException|IllegalArgumentException ex) {
                req.getServletContext().log("账号状态校验失败",ex);res.setStatus(503);res.setContentType("application/json;charset=UTF-8");
                res.getWriter().print(Json.encode(java.util.Map.of("message","暂时无法验证账号状态")));return;
            }
        }
        chain.doFilter(req,res);
    }
}
