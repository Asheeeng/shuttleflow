package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@TableName("admin")
public class Admin {
    private Long id;
    private String username;
    @JsonIgnore
    @TableField(exist = false)
    private String password;
    @JsonIgnore
    private String passwordHash;
    private String name;
    private String role;
}
