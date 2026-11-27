package com.sws.studyroom.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.Optional;

public record SessionIdentity(long userId, Role role) implements Serializable {
    private static final String ATTRIBUTE = SessionIdentity.class.getName();
    private static final String CSRF = SessionIdentity.class.getName() + ".csrf";

    public static String csrf(HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        synchronized (session) {
            if (!(session.getAttribute(CSRF) instanceof String)) session.setAttribute(CSRF, java.util.UUID.randomUUID().toString());
            return (String)session.getAttribute(CSRF);
        }
    }
    public static boolean validCsrf(HttpServletRequest request, String value) {
        HttpSession session = request.getSession(false);
        return value != null && session != null && value.equals(session.getAttribute(CSRF));
    }
    public static void refresh(HttpServletRequest request, SessionIdentity identity) {
        request.getSession().setAttribute(ATTRIBUTE, identity);
    }

    public enum Role { USER, ROOM_ADMIN, SUPER_ADMIN }

    public static void signIn(HttpServletRequest request, SessionIdentity identity) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(ATTRIBUTE, identity);
    }

    public static Optional<SessionIdentity> current(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(ATTRIBUTE) instanceof SessionIdentity identity) {
            return Optional.of(identity);
        }
        return Optional.empty();
    }

    public static void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}

