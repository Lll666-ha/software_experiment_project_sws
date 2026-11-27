package com.sws.studyroom.common;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public abstract class PendingServlet extends HttpServlet {
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"NOT_IMPLEMENTED\"}");
    }
}

