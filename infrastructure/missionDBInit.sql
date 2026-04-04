CREATE TABLE IF NOT EXISTS missions (
    id UUID PRIMARY KEY,
    rover_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    payload JSONB NOT NULL
);

SET citus.shard_count = 2;

SELECT create_distributed_table('missions', 'id');

CREATE INDEX idx_missions_rover_id ON missions (rover_id);
CREATE INDEX idx_missions_status ON missions (status);