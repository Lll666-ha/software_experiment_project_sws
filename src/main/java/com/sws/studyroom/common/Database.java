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
        Connection connection = DriverManager.getConnection(
                requiredEnvironment("SWS_DB_URL"),
                requiredEnvironment("SWS_DB_USER"),
                requiredEnvironment("SWS_DB_PASSWORD"));
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (var statement = connection.createStatement()) { statement.execute("SET time_zone = '+08:00'"); }
            return connection;
        } catch (SQLException ex) { connection.close(); throw ex; }
    }

    private static String requiredEnvironment(String name) throws SQLException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new SQLException("Missing environment variable: " + name);
        }
        return value;
    }
}

