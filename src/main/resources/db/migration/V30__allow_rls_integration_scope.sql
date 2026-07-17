-- The development feed is bound to one configured workspace and actor. It receives no global
-- SYSTEM visibility; existing workspace + actor policies continue to enforce row isolation.
CREATE OR REPLACE FUNCTION app_workspace_matches(row_workspace_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT app_current_scope() IN (
               'REST', 'LINE', 'BACKGROUND', 'LOCAL', 'TEST', 'INTEGRATION')
       AND row_workspace_id = app_current_workspace_id()
$$;
