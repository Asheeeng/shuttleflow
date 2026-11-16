package com.gzu.shuttle.service;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Ensures password hash columns and migrates legacy credentials without dropping account rows. */
@Component
public class PasswordMigrationRunner implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(PasswordMigrationRunner.class);
    private final JdbcTemplate jdbc;
    private final PasswordHashService passwordHashes;

    public PasswordMigrationRunner(JdbcTemplate jdbc, PasswordHashService passwordHashes) {
        this.jdbc = jdbc;
        this.passwordHashes = passwordHashes;
    }

    @Override
    public void afterPropertiesSet() {
        ensureColumn("driver", "password_hash");
        ensureColumn("admin", "password_hash");
        migrate("driver");
        migrate("admin");
    }

    private void ensureColumn(String table, String column) {
        List<Map<String, Object>> columns = jdbc.queryForList("SHOW COLUMNS FROM `" + table + "` LIKE ?", column);
        if (columns.isEmpty()) jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` VARCHAR(100) NULL");
    }

    private void migrate(String table) {
        if (!hasColumn(table, "password")) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,password,password_hash FROM `" + table + "` WHERE password_hash IS NULL OR password_hash='' ");
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String legacy = row.get("password") == null ? "" : row.get("password").toString();
            if (legacy.isEmpty()) continue;
            String hash = legacy.startsWith("pbkdf2-sha256$") ? legacy : passwordHashes.encode(legacy);
            jdbc.update("UPDATE `" + table + "` SET password_hash=?,password='' WHERE id=?", hash, row.get("id"));
            migrated++;
        }
        log.info("{} 表口令迁移完成：转换 {} 条旧口令", table, migrated);
    }

    private boolean hasColumn(String table, String column) {
        List<Map<String, Object>> columns = jdbc.queryForList("SHOW COLUMNS FROM `" + table + "` LIKE ?", column);
        return !columns.isEmpty();
    }
}
