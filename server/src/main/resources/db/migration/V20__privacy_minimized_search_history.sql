-- Project policy supplied by the user: five calendar years; not a legal-policy assertion.
-- No existing rows are rewritten or deleted by this migration.
ALTER TABLE analytics.search_event ADD COLUMN query_repr jsonb;
ALTER TABLE analytics.search_event ADD COLUMN expires_at timestamptz;
ALTER TABLE analytics.search_event ADD COLUMN feedback_token_hash varchar(64);
ALTER TABLE analytics.search_event ADD COLUMN returned_page integer;
ALTER TABLE analytics.search_event ADD COLUMN returned_size integer;
ALTER TABLE analytics.search_event ADD CONSTRAINT search_query_repr_object CHECK (query_repr IS NULL OR jsonb_typeof(query_repr)='object');
ALTER TABLE analytics.search_event ADD CONSTRAINT search_feedback_hash_format CHECK (feedback_token_hash IS NULL OR feedback_token_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE analytics.search_event ADD CONSTRAINT search_returned_page_bounds CHECK (returned_page IS NULL OR returned_page>=0);
ALTER TABLE analytics.search_event ADD CONSTRAINT search_returned_size_bounds CHECK (returned_size IS NULL OR returned_size BETWEEN 1 AND 100);
CREATE INDEX search_event_expiry_idx ON analytics.search_event(expires_at,id);

CREATE TABLE analytics.search_result (
    search_event_id uuid NOT NULL REFERENCES analytics.search_event(id) ON DELETE CASCADE,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 0 AND 99),
    dataset_id uuid NOT NULL,
    publication_no integer NOT NULL CHECK (publication_no>0),
    PRIMARY KEY(search_event_id,ordinal),
    UNIQUE(search_event_id,dataset_id)
);
CREATE TABLE analytics.search_feedback (
    search_event_id uuid PRIMARY KEY REFERENCES analytics.search_event(id) ON DELETE CASCADE,
    rating varchar(20) NOT NULL CHECK(rating IN ('HELPFUL','NOT_HELPFUL')),
    rated_at timestamptz NOT NULL
);
COMMENT ON COLUMN analytics.search_event.query_repr IS 'Allowlisted query intent/concept codes only. No raw query, hash, identity, IP or free text.';
COMMENT ON COLUMN analytics.search_event.expires_at IS 'UTC occurrence plus five calendar years; Feb29 clamps to Feb28.';
COMMENT ON TABLE analytics.search_result IS 'IDs and publication versions of the public response page; no copied titles or user identity.';
COMMENT ON TABLE analytics.search_feedback IS 'At most one immutable selected rating per random search event. No user identity or free text.';
