package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@TableName("driver")
public class Driver {
    private Long id;
    private String name;
    private String phone;
    @JsonIgnore
    @TableField(exist = false)
    private String password;
    @JsonIgnore
    private String passwordHash;
    private Long defaultVehicleId;
    private String status;
    private Integer enabled;
}
