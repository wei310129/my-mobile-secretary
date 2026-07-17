package com.aproject.aidriven.mymobilesecretary.account.workspace;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.hibernate.Session;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

/** Binds the trusted application scope to transaction-local PostgreSQL settings for RLS. */
final class WorkspaceRlsJpaDialect extends HibernateJpaDialect {

    private static final String SET_SCOPE_SQL = """
            SELECT set_config('app.scope', ?, true),
                   set_config('app.workspace_id', ?, true),
                   set_config('app.actor_id', ?, true)
            """;

    @Override
    public Object beginTransaction(EntityManager entityManager,
                                   TransactionDefinition definition)
            throws PersistenceException, SQLException, TransactionException {
        Object transactionData = super.beginTransaction(entityManager, definition);
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        entityManager.unwrap(Session.class).doWork(connection -> bindScope(connection, context));
        return transactionData;
    }

    static void bindScope(java.sql.Connection connection, WorkspaceContext context)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_SCOPE_SQL)) {
            statement.setString(1, context.channel().name());
            statement.setString(2, context.workspaceId().toString());
            statement.setString(3, context.actorId().toString());
            statement.execute();
        }
    }
}
