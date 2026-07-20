CREATE TABLE game_sessions (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    white_player VARCHAR(50) NOT NULL,
    black_player VARCHAR(50) NOT NULL,
    white_token_hash VARCHAR(64) NOT NULL,
    black_token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_game_sessions_status_updated
    ON game_sessions (status, updated_at);

CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_idempotency_game_key UNIQUE (game_id, idempotency_key)
);

CREATE INDEX idx_idempotency_records_created_at
    ON idempotency_records (created_at);

