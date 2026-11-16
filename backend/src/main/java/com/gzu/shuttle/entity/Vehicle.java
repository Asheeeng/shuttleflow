package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("vehicle")
public class Vehicle {
    private Long id;
    private String plateNo;
    private Long lineId;
    private Integer capacity;
    private String status;
    private Integer currentLoad;
    private Long currentStationId;
    private String direction;
    private Integer runningToday;
    private BigDecimal lat;
    private BigDecimal lng;
    private LocalDateTime lastReportTime;
}