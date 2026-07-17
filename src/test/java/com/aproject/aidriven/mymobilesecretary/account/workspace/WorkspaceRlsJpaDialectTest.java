package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceRlsJpaDialectTest {

    @Test
    void bindsChannelWorkspaceAndActorAsTransactionLocalSettings() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(statement);
        WorkspaceContext context = new WorkspaceContext(
                UUID.randomUUID(), UUID.randomUUID(), WorkspaceChannel.LINE);

        WorkspaceRlsJpaDialect.bindScope(connection, context);

        verify(statement).setString(1, "LINE");
        verify(statement).setString(2, context.workspaceId().toString());
        verify(statement).setString(3, context.actorId().toString());
        verify(statement).execute();
        verify(statement).close();
    }
}
