package com.sws.studyroom.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
    private Database() {
    }

    public static Connection open() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("MySQL JDBC driver is missing", exception);
        }
        return DriverManager.getConnection(
                requiredEnvironment("SWS_DB_URL"),
                requiredEnvironment("SWS_DB_USER"),
                requiredEnvironment("SWS_DB_PASSWORD"));
    }

    private static String requiredEnvironment(String name) throws SQLException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new SQLException("Missing environment variable: " + name);
        }
        return value;
    }
}
