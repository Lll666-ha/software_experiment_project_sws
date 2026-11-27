USE studyroom;

CREATE TABLE IF NOT EXISTS room_profile (
    room_id BIGINT NOT NULL PRIMARY KEY,
    has_projector BOOLEAN NOT NULL DEFAULT FALSE,
    has_whiteboard BOOLEAN NOT NULL DEFAULT FALSE,
    has_outlets BOOLEAN NOT NULL DEFAULT FALSE,
    usage_rules VARCHAR(500) NULL,
    panorama_url VARCHAR(512) NULL,
    CONSTRAINT fk_room_profile FOREIGN KEY (room_id) REFERENCES room(room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
