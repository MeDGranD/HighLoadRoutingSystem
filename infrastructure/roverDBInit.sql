CREATE TABLE IF NOT EXISTS models (
    id UUID PRIMARY KEY,
    power FLOAT8 NOT NULL,
    max_charge FLOAT8 NOT NULL,
    capacity FLOAT8 NOT NULL,
    max_speed FLOAT8 NOT NULL,
    avg_speed FLOAT8 NOT NULL
);

CREATE TABLE IF NOT EXISTS rovers (
    id UUID PRIMARY KEY,
    model_id UUID NOT NULL,
    last_seen_lat FLOAT8 NOT NULL,
    last_seen_lon FLOAT8 NOT NULL,
    last_seen_battery FLOAT8 NOT NULL,
    status VARCHAR(50) NOT NULL,
    last_seen TIMESTAMP WITH TIME ZONE NOT NULL,
    
    CONSTRAINT fk_rover_model FOREIGN KEY (model_id) REFERENCES models (id)
);

CREATE INDEX idx_rovers_status ON rovers (status);
CREATE INDEX idx_rovers_last_seen ON rovers (last_seen);
CREATE INDEX idx_rovers_location ON rovers (last_seen_lat, last_seen_lon);