-- Optional: only for a manually managed MySQL instance.
-- Docker Compose and scripts/local-mysql.py already create this database.
CREATE DATABASE IF NOT EXISTS campus_shuttle
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- Create a local application user with a private password outside this file.
-- Application tables are managed exclusively by Flyway.
