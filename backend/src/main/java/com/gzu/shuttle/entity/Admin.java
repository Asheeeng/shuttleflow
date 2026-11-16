package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("admin")
public class Admin {
    private Long id;
    private String username;
    private String password;
    private String name;
    private String role;
}