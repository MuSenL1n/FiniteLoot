CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY,
    description TEXT NOT NULL,
    installed_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS containers (
    id TEXT PRIMARY KEY,
    world_uid TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    partner_x INTEGER,
    partner_y INTEGER,
    partner_z INTEGER,
    loot_table TEXT,
    template_contents BLOB,
    max_claims INTEGER NOT NULL CHECK (max_claims > 0),
    claim_count INTEGER NOT NULL DEFAULT 0 CHECK (claim_count >= 0),
    manual INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_containers_location
    ON containers(world_uid, x, y, z);

CREATE TABLE IF NOT EXISTS claims (
    container_id TEXT NOT NULL REFERENCES containers(id) ON DELETE CASCADE,
    player_uuid TEXT NOT NULL,
    player_name TEXT NOT NULL,
    counted INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'COMPLETED')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (container_id, player_uuid)
);

CREATE TABLE IF NOT EXISTS personal_inventories (
    container_id TEXT NOT NULL,
    player_uuid TEXT NOT NULL,
    contents BLOB NOT NULL,
    completed INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (container_id, player_uuid),
    FOREIGN KEY (container_id, player_uuid)
        REFERENCES claims(container_id, player_uuid) ON DELETE CASCADE
);

