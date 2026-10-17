CREATE TABLE IF NOT EXISTS whatsapp_message_logs (
    id BIGSERIAL PRIMARY KEY,
    external_message_id VARCHAR(128) UNIQUE,
    phone_number VARCHAR(128) NOT NULL,
    direction VARCHAR(16) NOT NULL,
    message_body TEXT NOT NULL,
    timestamp TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_message_logs_phone_timestamp_id 
    ON whatsapp_message_logs (phone_number, timestamp DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_message_logs_timestamp 
    ON whatsapp_message_logs (timestamp DESC);
