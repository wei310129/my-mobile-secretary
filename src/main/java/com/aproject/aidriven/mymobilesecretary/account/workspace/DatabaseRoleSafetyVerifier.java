package com.aproject.aidriven.mymobilesecretary.account.workspace;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Refuses production startup when the runtime database role can bypass PostgreSQL RLS. */
@Component
@ConditionalOnProperty(prefix = "app.security", name = "enabled", havingValue = "true")
public final class DatabaseRoleSafetyVerifier implements SmartInitializingSingleton {

    private static final String ROLE_QUERY = """
            SELECT rolname, rolsuper, rolbypassrls
            FROM pg_roles
            WHERE rolname = current_user
            """;

    private final JdbcTemplate jdbcTemplate;

    public DatabaseRoleSafetyVerifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RoleSafety role = jdbcTemplate.queryForObject(ROLE_QUERY,
                (rows, rowNumber) -> new RoleSafety(
                        rows.getString("rolname"),
                        rows.getBoolean("rolsuper"),
                        rows.getBoolean("rolbypassrls")));
        if (role == null || role.superuser() || role.bypassRls()) {
            String roleName = role == null ? "unknown" : role.name();
            throw new IllegalStateException("Database runtime role '" + roleName
                    + "' must be NOSUPERUSER and NOBYPASSRLS when app.security.enabled=true");
        }
    }

    record RoleSafety(String name, boolean superuser, boolean bypassRls) {
    }
}
