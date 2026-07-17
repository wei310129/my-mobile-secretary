package com.aproject.aidriven.mymobilesecretary.account.workspace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DatabaseRoleSafetyVerifierTest {

    @Test
    void acceptsRestrictedRuntimeRole() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubRole(jdbcTemplate, new DatabaseRoleSafetyVerifier.RoleSafety(
                "mms_runtime", false, false));

        assertThatCode(() -> new DatabaseRoleSafetyVerifier(jdbcTemplate)
                .afterSingletonsInstantiated()).doesNotThrowAnyException();
    }

    @Test
    void rejectsSuperuserOrBypassRlsRole() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        stubRole(jdbcTemplate, new DatabaseRoleSafetyVerifier.RoleSafety(
                "mms_owner", true, true));

        assertThatThrownBy(() -> new DatabaseRoleSafetyVerifier(jdbcTemplate)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOSUPERUSER")
                .hasMessageContaining("NOBYPASSRLS");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void stubRole(JdbcTemplate jdbcTemplate,
                                 DatabaseRoleSafetyVerifier.RoleSafety role) {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class))).thenReturn(role);
    }
}
