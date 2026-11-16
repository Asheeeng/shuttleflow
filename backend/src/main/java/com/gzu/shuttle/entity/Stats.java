package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("stats")
public class Stats {
    private Long id;
    private LocalDate statDate;
    private Long lineId;
    private Long stationId;
    private String timeSlot;
    private Integer waitingAvg;
    private Integer leftoverAvg;
    private BigDecimal loadRate;
    private BigDecimal taskCompleteRate;
    private String dataSource;
}