CREATE TABLE resource_locks
(
    lock_key   VARCHAR(64) PRIMARY KEY,
    touched_at BIGINT NOT NULL
);
CREATE INDEX idx_resource_lock_cleanup ON resource_locks (touched_at);

CREATE TABLE users
(
    id              VARCHAR(64) PRIMARY KEY,
    email           VARCHAR(508)  NOT NULL UNIQUE,
    username        VARCHAR(24)   NOT NULL UNIQUE,
    password_hash   VARCHAR(512)  NOT NULL,
    display_name    VARCHAR(80)   NOT NULL,
    bio             VARCHAR(1000) NOT NULL,
    avatar_asset_id VARCHAR(64),
    email_verified  BOOLEAN       NOT NULL,
    theme           VARCHAR(16)   NOT NULL,
    motion          VARCHAR(16)   NOT NULL,
    auto_play_media BOOLEAN       NOT NULL,
    created_at      BIGINT        NOT NULL
);

CREATE TABLE sessions
(
    id           VARCHAR(64) PRIMARY KEY,
    user_id      VARCHAR(64) NOT NULL,
    refresh_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at   BIGINT      NOT NULL,
    created_at   BIGINT      NOT NULL,
    revoked      BOOLEAN     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_session_user ON sessions (user_id);

CREATE TABLE verification_codes
(
    user_id    VARCHAR(64) PRIMARY KEY,
    code_hash  VARCHAR(64) NOT NULL,
    expires_at BIGINT      NOT NULL,
    sent_at    BIGINT      NOT NULL,
    attempts   INTEGER     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE password_resets
(
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    expires_at BIGINT      NOT NULL,
    consumed   BOOLEAN     NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE assets
(
    id            VARCHAR(64) PRIMARY KEY,
    owner_id      VARCHAR(64)  NOT NULL,
    purpose       VARCHAR(16)  NOT NULL,
    media_type    VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    mime_type     VARCHAR(100) NOT NULL,
    storage_key   VARCHAR(255),
    playback_key  VARCHAR(255),
    thumbnail_key VARCHAR(255),
    failure_code  VARCHAR(100),
    size_bytes    BIGINT       NOT NULL,
    created_at    BIGINT       NOT NULL,
    width         INTEGER,
    height        INTEGER,
    duration_ms   BIGINT,
    cleaned_at    BIGINT,
    FOREIGN KEY (owner_id) REFERENCES users (id)
);
CREATE INDEX idx_asset_cleanup ON assets (cleaned_at, id);

CREATE TABLE uploads
(
    id            VARCHAR(64) PRIMARY KEY,
    asset_id      VARCHAR(64) NOT NULL UNIQUE,
    owner_id      VARCHAR(64) NOT NULL,
    state         VARCHAR(16) NOT NULL,
    expires_at    BIGINT      NOT NULL,
    created_at    BIGINT      NOT NULL,
    expected_size BIGINT      NOT NULL,
    finished_at   BIGINT,
    FOREIGN KEY (asset_id) REFERENCES assets (id),
    FOREIGN KEY (owner_id) REFERENCES users (id)
);
CREATE INDEX idx_upload_cleanup ON uploads (state, finished_at, id);

CREATE TABLE music_links
(
    id              VARCHAR(64) PRIMARY KEY,
    owner_id        VARCHAR(64)   NOT NULL,
    provider        VARCHAR(24)   NOT NULL,
    source_url      VARCHAR(4096) NOT NULL,
    title           VARCHAR(600),
    artist          VARCHAR(600),
    cover_url       VARCHAR(4096),
    metadata_status VARCHAR(16)   NOT NULL,
    created_at      BIGINT        NOT NULL,
    FOREIGN KEY (owner_id) REFERENCES users (id)
);

CREATE TABLE works
(
    id              VARCHAR(64) PRIMARY KEY,
    owner_id        VARCHAR(64)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    version         BIGINT       NOT NULL,
    title           VARCHAR(240) NOT NULL,
    body            MEDIUMTEXT   NOT NULL,
    cover_asset_id  VARCHAR(64),
    music_link_id   VARCHAR(64),
    failure_code    VARCHAR(100),
    publish_task_id VARCHAR(64),
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL,
    published_at    BIGINT,
    FOREIGN KEY (owner_id) REFERENCES users (id),
    FOREIGN KEY (cover_asset_id) REFERENCES assets (id),
    FOREIGN KEY (music_link_id) REFERENCES music_links (id)
);
CREATE INDEX idx_work_public ON works (status, published_at, id);
CREATE INDEX idx_work_owner ON works (owner_id, created_at, id);

CREATE TABLE work_assets
(
    work_id  VARCHAR(64) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    position INTEGER     NOT NULL,
    PRIMARY KEY (work_id, asset_id),
    UNIQUE (work_id, position),
    FOREIGN KEY (work_id) REFERENCES works (id),
    FOREIGN KEY (asset_id) REFERENCES assets (id)
);

CREATE TABLE work_likes
(
    user_id    VARCHAR(64) NOT NULL,
    work_id    VARCHAR(64) NOT NULL,
    created_at BIGINT      NOT NULL,
    PRIMARY KEY (user_id, work_id),
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (work_id) REFERENCES works (id)
);

CREATE TABLE bookmarks
(
    id         VARCHAR(64) PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    work_id    VARCHAR(64) NOT NULL,
    created_at BIGINT      NOT NULL,
    UNIQUE (user_id, work_id),
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (work_id) REFERENCES works (id)
);
CREATE INDEX idx_bookmark_page ON bookmarks (user_id, created_at, id);

CREATE TABLE comments
(
    id         VARCHAR(64) PRIMARY KEY,
    work_id    VARCHAR(64) NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    body       TEXT        NOT NULL,
    created_at BIGINT      NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (work_id) REFERENCES works (id)
);
CREATE INDEX idx_comment_page ON comments (work_id, created_at, id);

CREATE TABLE feed_sessions
(
    id         VARCHAR(64) PRIMARY KEY,
    cutoff     BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);

CREATE TABLE feed_sessions
(
    id         VARCHAR(64) PRIMARY KEY,
    cutoff     BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);

CREATE TABLE feed_items
(
    session_id VARCHAR(64) NOT NULL,
    position   BIGINT      NOT NULL,
    work_id    VARCHAR(64) NOT NULL,
    PRIMARY KEY (session_id, position),
    UNIQUE (session_id, work_id),
    FOREIGN KEY (session_id) REFERENCES feed_sessions (id)
);

CREATE TABLE idempotency
(
    id            VARCHAR(64) PRIMARY KEY,
    body_hash     VARCHAR(64) NOT NULL,
    response_json MEDIUMTEXT,
    expires_at    BIGINT      NOT NULL,
    lease_until   BIGINT      NOT NULL,
    lease_token   VARCHAR(64)
);

CREATE TABLE rate_limits
(
    id         VARCHAR(64) PRIMARY KEY,
    hits       INTEGER NOT NULL,
    expires_at BIGINT  NOT NULL
);

CREATE TABLE background_tasks
(
    id           VARCHAR(64) PRIMARY KEY,
    kind         VARCHAR(32) NOT NULL,
    payload      MEDIUMTEXT  NOT NULL,
    state        VARCHAR(16) NOT NULL,
    attempts     INTEGER     NOT NULL,
    available_at BIGINT      NOT NULL,
    lease_until  BIGINT      NOT NULL,
    lease_token  VARCHAR(64),
    last_error   VARCHAR(200),
    created_at   BIGINT      NOT NULL,
    finished_at  BIGINT
);
CREATE INDEX idx_task_claim ON background_tasks (state, available_at, lease_until);
CREATE INDEX idx_task_cleanup ON background_tasks (state, finished_at, id);