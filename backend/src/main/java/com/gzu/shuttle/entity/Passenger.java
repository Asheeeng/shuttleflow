package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("passenger")
public class Passenger {
    private Long id;
    private String openId;
    private String name;
    private String phone;
    private LocalDateTime createdAt;
}