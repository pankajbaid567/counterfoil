package dev.counterfoil.config;

import java.sql.SQLException;
import java.util.Optional;
import org.postgresql.util.PSQLException;

/** Helpers to read PostgreSQL error details out of Spring's exception wrappers. */
public final class SqlErrors {
    private SqlErrors() {}

    public static Optional<PSQLException> psql(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException p) {
                return Optional.of(p);
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return Optional.empty();
    }

    public static Optional<String> sqlState(Throwable t) {
        return psql(t).map(SQLException::getSQLState);
    }

    public static Optional<String> constraint(Throwable t) {
        return psql(t).map(p -> p.getServerErrorMessage() == null ? null : p.getServerErrorMessage().getConstraint());
    }

    public static boolean isUniqueViolation(Throwable t, String constraint) {
        return sqlState(t).filter("23505"::equals).isPresent() && constraint(t).filter(constraint::equals).isPresent();
    }
}
