ALTER TABLE game_sessions
    ADD COLUMN base_ms            INT,
    ADD COLUMN increment_ms       INT,
    ADD COLUMN white_remaining_ms BIGINT,
    ADD COLUMN black_remaining_ms BIGINT,
    ADD COLUMN turn_started_at    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN turn_deadline_at   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN result_winner      VARCHAR(5),
    ADD COLUMN result_reason      VARCHAR(20),
    ADD COLUMN draw_offered_by    VARCHAR(5),
    ADD COLUMN white_draw_offers  SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN black_draw_offers  SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN rematch_offered_by VARCHAR(5),
    ADD COLUMN rematch_game_id    UUID REFERENCES game_sessions(id),
    ADD COLUMN finished_at        TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_game_turn_deadline ON game_sessions (turn_deadline_at) WHERE status = 'IN_PROGRESS';

CREATE TABLE system_heartbeat (
    id            SMALLINT PRIMARY KEY CHECK (id = 1),
    last_alive_at TIMESTAMP WITH TIME ZONE NOT NULL
);
INSERT INTO system_heartbeat VALUES (1, now());
