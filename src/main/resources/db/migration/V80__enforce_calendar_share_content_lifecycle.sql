CREATE FUNCTION revoke_calendar_content_grants(
    p_share_id UUID,
    p_attachment_binding_id UUID,
    p_knowledge_excerpt_id UUID,
    p_media_id BIGINT,
    p_reason VARCHAR,
    p_now TIMESTAMPTZ)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO calendar_share_outbox (
        id, operation_request_hash, event_type, share_id,
        content_grant_id, grantee_user_id, payload_text,
        status, created_at, workspace_id, created_by_user_id)
    SELECT gen_random_uuid(),
           md5(grant_row.id::text || ':' || p_reason)
               || md5(p_reason || ':' || grant_row.id::text),
           'CONTENT_REVOKED', grant_row.share_id, grant_row.id,
           grant_row.grantee_user_id,
           'Calendar content access revoked: ' || p_reason,
           'PENDING', p_now, grant_row.workspace_id,
           grant_row.created_by_user_id
    FROM calendar_share_content_grant grant_row
    WHERE grant_row.status = 'ACTIVE'
      AND (p_share_id IS NULL OR grant_row.share_id = p_share_id)
      AND (
          p_attachment_binding_id IS NULL
          OR grant_row.attachment_binding_id = p_attachment_binding_id)
      AND (
          p_knowledge_excerpt_id IS NULL
          OR grant_row.knowledge_excerpt_id = p_knowledge_excerpt_id)
      AND (p_media_id IS NULL OR grant_row.media_id = p_media_id)
    ON CONFLICT DO NOTHING;

    UPDATE calendar_share_content_grant grant_row
    SET status = 'REVOKED',
        grant_revision = grant_revision + 1,
        revoke_request_hash =
            md5(grant_row.id::text || ':' || p_reason)
                || md5(p_reason || ':' || grant_row.id::text),
        revoke_payload_hash =
            md5(grant_row.id::text || ':PAYLOAD:' || p_reason)
                || md5(p_reason || ':PAYLOAD:' || grant_row.id::text),
        revoked_at = p_now,
        updated_at = p_now
    WHERE grant_row.status = 'ACTIVE'
      AND (p_share_id IS NULL OR grant_row.share_id = p_share_id)
      AND (
          p_attachment_binding_id IS NULL
          OR grant_row.attachment_binding_id = p_attachment_binding_id)
      AND (
          p_knowledge_excerpt_id IS NULL
          OR grant_row.knowledge_excerpt_id = p_knowledge_excerpt_id)
      AND (p_media_id IS NULL OR grant_row.media_id = p_media_id);
END;
$$;

CREATE FUNCTION revoke_content_after_calendar_share()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ACTIVE' AND NEW.status = 'REVOKED' THEN
        PERFORM revoke_calendar_content_grants(
            NEW.id, NULL, NULL, NULL, 'SHARE_REVOKED', NEW.updated_at);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION revoke_content_after_calendar_attachment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE' THEN
        PERFORM revoke_calendar_content_grants(
            NULL, NEW.id, NULL, NULL, 'ATTACHMENT_' || NEW.status,
            NEW.updated_at);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION revoke_content_after_calendar_excerpt()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'APPROVED' AND NEW.status <> 'APPROVED' THEN
        PERFORM revoke_calendar_content_grants(
            NULL, NULL, NEW.id, NULL, 'EXCERPT_' || NEW.status,
            NEW.updated_at);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION revoke_content_after_stored_media()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'AVAILABLE' AND NEW.status = 'DELETED' THEN
        PERFORM revoke_calendar_content_grants(
            NULL, NULL, NULL, NEW.id, 'MEDIA_DELETED', NEW.deleted_at);
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION revoke_share_after_calendar_plan()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ACTIVE' AND NEW.status <> 'ACTIVE' THEN
        INSERT INTO calendar_share_outbox (
            id, operation_request_hash, event_type, share_id,
            content_grant_id, grantee_user_id, payload_text,
            status, created_at, workspace_id, created_by_user_id)
        SELECT gen_random_uuid(),
               md5(share.id::text || ':PLAN_' || NEW.status)
                   || md5('PLAN_' || NEW.status || ':' || share.id::text),
               'SHARE_REVOKED', share.id, NULL, share.grantee_user_id,
               'Calendar share revoked after plan lifecycle change',
               'PENDING', NEW.updated_at, share.workspace_id,
               share.created_by_user_id
        FROM calendar_share share
        WHERE share.plan_id = NEW.id
          AND share.workspace_id = NEW.workspace_id
          AND share.created_by_user_id = NEW.created_by_user_id
          AND share.status = 'ACTIVE'
        ON CONFLICT DO NOTHING;

        UPDATE calendar_share share
        SET status = 'REVOKED',
            share_revision = share_revision + 1,
            revoke_request_hash =
                md5(share.id::text || ':PLAN_' || NEW.status)
                    || md5('PLAN_' || NEW.status || ':' || share.id::text),
            revoke_payload_hash =
                md5(share.id::text || ':PLAN_PAYLOAD_' || NEW.status)
                    || md5(
                        'PLAN_PAYLOAD_' || NEW.status || ':'
                        || share.id::text),
            revoked_at = NEW.updated_at,
            updated_at = NEW.updated_at
        WHERE share.plan_id = NEW.id
          AND share.workspace_id = NEW.workspace_id
          AND share.created_by_user_id = NEW.created_by_user_id
          AND share.status = 'ACTIVE';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_calendar_share_content_lifecycle
AFTER UPDATE OF status ON calendar_share
FOR EACH ROW EXECUTE FUNCTION revoke_content_after_calendar_share();

CREATE TRIGGER trg_calendar_attachment_content_lifecycle
AFTER UPDATE OF status ON calendar_attachment_binding
FOR EACH ROW EXECUTE FUNCTION revoke_content_after_calendar_attachment();

CREATE TRIGGER trg_calendar_excerpt_content_lifecycle
AFTER UPDATE OF status ON calendar_knowledge_excerpt
FOR EACH ROW EXECUTE FUNCTION revoke_content_after_calendar_excerpt();

CREATE TRIGGER trg_stored_media_calendar_content_lifecycle
AFTER UPDATE OF status ON stored_media
FOR EACH ROW EXECUTE FUNCTION revoke_content_after_stored_media();

CREATE TRIGGER trg_calendar_plan_share_lifecycle
AFTER UPDATE OF status ON calendar_plan
FOR EACH ROW EXECUTE FUNCTION revoke_share_after_calendar_plan();
