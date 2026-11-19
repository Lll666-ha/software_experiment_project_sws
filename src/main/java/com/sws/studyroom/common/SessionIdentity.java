package com.sws.studyroom.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.Optional;

public record SessionIdentity(long userId, Role role) implements Serializable {
    private static final String ATTRIBUTE = SessionIdentity.class.getName();

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
