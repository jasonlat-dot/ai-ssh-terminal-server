-- SQLite 初始化脚本。
-- 只创建不存在的表和索引，不删除已有数据；应用每次以 SQLite 模式启动时均可安全执行。

CREATE TABLE IF NOT EXISTS chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT,
    tool_name TEXT,
    tool_call_id TEXT,
    priority TEXT DEFAULT 'MEDIUM',
    token_count INTEGER DEFAULT 0,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_time
    ON chat_message (session_id, created_at);

CREATE TABLE IF NOT EXISTS chat_milestone (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    type TEXT NOT NULL,
    content TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_milestone_session_time
    ON chat_milestone (session_id, created_at);

CREATE TABLE IF NOT EXISTS chat_session (
    id TEXT PRIMARY KEY,
    agent_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    title TEXT,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
    message_count INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_chat_session_user_agent
    ON chat_session (user_id, agent_id);

CREATE TABLE IF NOT EXISTS core_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL DEFAULT 'default',
    scope TEXT NOT NULL,
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    keywords TEXT,
    content TEXT,
    priority INTEGER NOT NULL DEFAULT 3,
    source_session_id TEXT,
    use_count INTEGER NOT NULL DEFAULT 1,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    last_used_at TEXT DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_core_memory_user_priority
    ON core_memory (user_id, priority);

CREATE INDEX IF NOT EXISTS idx_core_memory_user_last_used
    ON core_memory (user_id, last_used_at);

CREATE INDEX IF NOT EXISTS idx_core_memory_keywords
    ON core_memory (keywords);

CREATE TABLE IF NOT EXISTS long_term_memory (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    session_id TEXT,
    memory_type TEXT NOT NULL,
    memory_key TEXT NOT NULL,
    content TEXT NOT NULL,
    keywords TEXT,
    source_role TEXT,
    confidence NUMERIC DEFAULT 0.50,
    hit_count INTEGER DEFAULT 1,
    created_at TEXT DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_long_term_memory_identity UNIQUE (user_id, memory_type, memory_key)
);

CREATE INDEX IF NOT EXISTS idx_long_term_memory_user_time
    ON long_term_memory (user_id, updated_at);

CREATE TABLE IF NOT EXISTS ssh_connection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    connection_id TEXT NOT NULL UNIQUE,
    connection_name TEXT NOT NULL,
    host TEXT NOT NULL,
    port INTEGER NOT NULL DEFAULT 22,
    username TEXT NOT NULL,
    auth_type INTEGER NOT NULL DEFAULT 1,
    password TEXT,
    private_key TEXT,
    encrypted INTEGER NOT NULL DEFAULT 1,
    user_id TEXT NOT NULL DEFAULT 'default',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_ssh_connection_user_id
    ON ssh_connection (user_id);

CREATE INDEX IF NOT EXISTS idx_ssh_connection_created_at
    ON ssh_connection (created_at);

CREATE TABLE IF NOT EXISTS ssh_connection_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    connection_id TEXT NOT NULL UNIQUE,
    connect_timeout INTEGER NOT NULL DEFAULT 10,
    keepalive_interval INTEGER NOT NULL DEFAULT 60,
    startup_command TEXT,
    compression INTEGER NOT NULL DEFAULT 0,
    strict_host_key_check INTEGER NOT NULL DEFAULT 1,
    known_hosts TEXT,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS file_asset (
    file_id TEXT PRIMARY KEY,
    owner_id TEXT,
    original_name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT,
    storage_id TEXT NOT NULL,
    bucket TEXT NOT NULL,
    object_key TEXT NOT NULL,
    object_version TEXT,
    etag TEXT,
    status TEXT NOT NULL,
    error_code TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_file_asset_storage_object UNIQUE (storage_id, bucket, object_key)
);

CREATE INDEX IF NOT EXISTS idx_file_asset_status_time
    ON file_asset (status, updated_at);

CREATE INDEX IF NOT EXISTS idx_file_asset_owner_time
    ON file_asset (owner_id, created_at);
