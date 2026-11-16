package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("driver")
public class Driver {
    private Long id;
    private String name;
    private String phone;
    private String password;
    private Long defaultVehicleId;
    private String status;
    private Integer enabled;
}