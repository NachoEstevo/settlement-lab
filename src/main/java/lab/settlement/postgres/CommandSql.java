package lab.settlement.postgres;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class CommandSql {
    private CommandSql() {}

    static PreparedStatement statement(Connection c, String sql, Object... args) throws SQLException {
        var ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        } catch (SQLException failure) {
            try {
                ps.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static void updateOne(Connection c, String sql, Object... args) throws SQLException {
        try (var ps = statement(c, sql, args)) {
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Expected exactly one row to change under the domain lock.");
            }
        }
    }
}
