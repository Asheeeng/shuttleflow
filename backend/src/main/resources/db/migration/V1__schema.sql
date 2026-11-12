CREATE TABLE app_user (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(60) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(80) NOT NULL,
 role VARCHAR(20) NOT NULL CHECK (role IN ('PASSENGER','DRIVER','ADMIN'))
);
CREATE TABLE shuttle_route (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 direction VARCHAR(100) NOT NULL,
 demo BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE route_stop (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 route_id BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL,
 stop_order INT NOT NULL CHECK (stop_order >= 0),
 seconds_from_previous INT NOT NULL CHECK (seconds_from_previous >= 0),
 UNIQUE(route_id, stop_order),
 FOREIGN KEY(route_id) REFERENCES shuttle_route(id)
);
CREATE TABLE vehicle (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(40) NOT NULL UNIQUE,
 capacity INT NOT NULL CHECK (capacity > 0)
);
CREATE TABLE run_task (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 driver_id BIGINT NOT NULL UNIQUE,
 vehicle_id BIGINT NOT NULL UNIQUE,
 route_id BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','STANDBY','STOPPED')),
 last_stop_id BIGINT,
 last_report_at TIMESTAMP(6),
 occupancy INT NOT NULL CHECK (occupancy >= 0),
 occupancy_confirmed BOOLEAN NOT NULL,
 occupancy_updated_at TIMESTAMP(6),
 FOREIGN KEY(driver_id) REFERENCES app_user(id),
 FOREIGN KEY(vehicle_id) REFERENCES vehicle(id),
 FOREIGN KEY(route_id) REFERENCES shuttle_route(id),
 FOREIGN KEY(last_stop_id) REFERENCES route_stop(id)
);
CREATE TABLE wait_request (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 route_id BIGINT NOT NULL,
 origin_stop_id BIGINT NOT NULL,
 destination_stop_id BIGINT NOT NULL,
 party_size INT NOT NULL CHECK (party_size > 0),
 boarded_count INT NOT NULL DEFAULT 0 CHECK (boarded_count >= 0),
 created_at TIMESTAMP(6) NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(20) NOT NULL CHECK (status IN ('WAITING','BOARDED','CANCELLED','EXPIRED')),
 active_user_id BIGINT UNIQUE,
 renewal_count INT NOT NULL DEFAULT 0,
 FOREIGN KEY(user_id) REFERENCES app_user(id),
 FOREIGN KEY(route_id) REFERENCES shuttle_route(id),
 FOREIGN KEY(origin_stop_id) REFERENCES route_stop(id),
 FOREIGN KEY(destination_stop_id) REFERENCES route_stop(id),
 FOREIGN KEY(active_user_id) REFERENCES app_user(id),
 CHECK (boarded_count <= party_size),
 CHECK ((status = 'WAITING' AND active_user_id IS NOT NULL AND active_user_id = user_id) OR (status <> 'WAITING' AND active_user_id IS NULL))
);
CREATE INDEX idx_wait_demand ON wait_request(status, expires_at, origin_stop_id);
CREATE TABLE arrival_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 task_id BIGINT NOT NULL,
 driver_id BIGINT NOT NULL,
 stop_id BIGINT NOT NULL,
 reported_at TIMESTAMP(6) NOT NULL,
 boarded INT,
 alighted INT,
 occupancy_after INT NOT NULL,
 counts_confirmed BOOLEAN NOT NULL,
 FOREIGN KEY(task_id) REFERENCES run_task(id),
 FOREIGN KEY(driver_id) REFERENCES app_user(id),
 FOREIGN KEY(stop_id) REFERENCES route_stop(id)
);
CREATE INDEX idx_arrival_time ON arrival_event(reported_at);
