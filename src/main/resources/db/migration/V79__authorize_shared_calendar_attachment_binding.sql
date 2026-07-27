CREATE POLICY rls_calendar_attachment_binding_grantee
    ON calendar_attachment_binding FOR SELECT
    USING (
        app_workspace_matches(workspace_id)
        AND status = 'ACTIVE'
        AND EXISTS (
            SELECT 1
            FROM calendar_share_content_grant grant_row
            WHERE grant_row.attachment_binding_id =
                    calendar_attachment_binding.id
              AND grant_row.media_id =
                    calendar_attachment_binding.media_id
              AND grant_row.plan_id =
                    calendar_attachment_binding.plan_id
              AND grant_row.workspace_id =
                    calendar_attachment_binding.workspace_id
              AND grant_row.created_by_user_id =
                    calendar_attachment_binding.created_by_user_id
              AND grant_row.status = 'ACTIVE'
              AND app_actor_matches(grant_row.grantee_user_id)));
