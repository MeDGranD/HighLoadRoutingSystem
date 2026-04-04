CREATE TABLE IF NOT EXISTS rover_telemetry
(
    rover_id UUID,
    lat Float64,
    lon Float64,
    battery Float32,
    ts DateTime64(3, 'UTC')
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(ts)
ORDER BY (rover_id, ts)
TTL toDateTime(ts) + INTERVAL 6 MONTH DELETE
SETTINGS index_granularity = 8192;