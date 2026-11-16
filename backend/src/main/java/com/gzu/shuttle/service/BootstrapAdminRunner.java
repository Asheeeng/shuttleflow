package com.gzu.shuttle.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gzu.shuttle.entity.Admin;
import com.gzu.shuttle.mapper.AdminMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** One-time local bootstrap. Existing administrator rows are never overwritten. */
@Component
public class BootstrapAdminRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);
    private final AdminMapper admins;
    private final PasswordHashService passwordHashes;
    @Value("${shuttle.bootstrap-admin.username:}") private String username;
    @Value("${shuttle.bootstrap-admin.password:}") private String password;

    public BootstrapAdminRunner(AdminMapper admins, PasswordHashService passwordHashes) {
        this.admins = admins;
        this.passwordHashes = passwordHashes;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (admins.selectCount(new QueryWrapper<Admin>()) > 0) {
            log.info("已存在管理员账号，跳过首次管理员引导");
            return;
        }
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty())
            throw new IllegalStateException("管理员账号为空。首次部署请设置 SHUTTLE_BOOTSTRAP_ADMIN_USERNAME 和 SHUTTLE_BOOTSTRAP_ADMIN_PASSWORD");
        String normalized = username.trim();
        if (normalized.length() > 30 || password.length() < 14 || password.length() > 128)
            throw new IllegalStateException("首次管理员用户名最多30字符，密码必须为14至128字符");
        Admin admin = new Admin();
        admin.setUsername(normalized);
        admin.setName("系统管理员");
        admin.setRole("admin");
        admin.setPasswordHash(passwordHashes.encode(password));
        admins.insert(admin);
        log.info("已创建首次系统管理员账号 {}。请从启动环境中删除一次性引导密码并重启服务", normalized);
    }
}
