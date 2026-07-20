DO $$
DECLARE
    tables_to_clear TEXT;
BEGIN
    SELECT string_agg(format('%I.%I', schemaname, tablename), ', ')
      INTO tables_to_clear
      FROM pg_tables
     WHERE schemaname = 'public'
       AND tablename NOT IN ('flyway_schema_history', 'spatial_ref_sys');

    IF tables_to_clear IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE ' || tables_to_clear || ' RESTART IDENTITY CASCADE';
    END IF;
END $$;
^^^

INSERT INTO app_user (id, display_name, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001',
        'Legacy Owner', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
^^^

INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000101',
        'Personal', 'PERSONAL',
        '00000000-0000-0000-0000-000000000001',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
^^^

INSERT INTO workspace_member
    (id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000201',
        '00000000-0000-0000-0000-000000000101',
        '00000000-0000-0000-0000-000000000001',
        'OWNER',
        '00000000-0000-0000-0000-000000000001',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
^^^
