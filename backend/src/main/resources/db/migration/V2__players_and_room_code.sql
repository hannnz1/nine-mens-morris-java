CREATE TABLE players (
    id           UUID PRIMARY KEY,
    nickname     VARCHAR(16) NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    kind         VARCHAR(10) NOT NULL DEFAULT 'HUMAN',
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_players_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_players_kind CHECK (kind IN ('HUMAN', 'BOT'))
);

ALTER TABLE game_sessions ADD COLUMN white_player_id UUID REFERENCES players(id);
ALTER TABLE game_sessions ADD COLUMN black_player_id UUID REFERENCES players(id);
ALTER TABLE game_sessions ADD COLUMN room_code       CHAR(6);
ALTER TABLE game_sessions ALTER COLUMN white_token_hash DROP NOT NULL;
ALTER TABLE game_sessions ALTER COLUMN black_token_hash DROP NOT NULL;

CREATE UNIQUE INDEX uk_game_room_code_active
    ON game_sessions (room_code) WHERE status IN ('WAITING_FOR_PLAYER', 'IN_PROGRESS');
CREATE INDEX idx_game_white_player ON game_sessions (white_player_id, updated_at DESC);
CREATE INDEX idx_game_black_player ON game_sessions (black_player_id, updated_at DESC);

CREATE TABLE player_idempotency_records (
    player_id           UUID         NOT NULL REFERENCES players(id) ON DELETE CASCADE,
    idempotency_key     VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64)     NOT NULL,
    response_json       TEXT         NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_player_idempotency PRIMARY KEY (player_id, idempotency_key)
);
