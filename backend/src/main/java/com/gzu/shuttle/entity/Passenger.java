package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("passenger")
public class Passenger {
    private Long id;
    private String openId;
    private String name;
    private String phone;
    @JsonIgnore
    private String passwordHash;
    @TableField(exist = false)
    @JsonIgnore
    private String password;
    private LocalDateTime createdAt;
}
