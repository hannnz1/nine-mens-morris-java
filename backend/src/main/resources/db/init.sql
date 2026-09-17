-- Final schema for a NEW, EMPTY database. This is not an upgrade script.
-- PostgreSQL: psql ... --set=ON_ERROR_STOP=1 --single-transaction --file=init.sql
-- Intentionally fail if tables already exist; do not hide a mismatched schema.
-- This same file is used by PostgreSQL, Docker initialization and H2 tests.

CREATE TABLE game_sessions (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL,
    white_player VARCHAR(50) NOT NULL,
    black_player VARCHAR(50),
    white_token_hash VARCHAR(64) NOT NULL,
    black_token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    state_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);


CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    game_id UUID NOT NULL REFERENCES game_sessions(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    response_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_idempotency_game_key UNIQUE (game_id, idempotency_key)
);

