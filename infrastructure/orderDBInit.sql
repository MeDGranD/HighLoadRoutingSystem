CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY,
    capacity FLOAT8 NOT NULL,
    from_lat FLOAT8 NOT NULL,
    from_lon FLOAT8 NOT NULL,
    to_lat FLOAT8 NOT NULL,
    to_lon FLOAT8 NOT NULL,
    status VARCHAR(50) NOT NULL,
    region_id INT NOT NULL
);

SET citus.shard_count = 2;

SELECT create_distributed_table('orders', 'id');

CREATE INDEX idx_orders_region_id ON orders (region_id);