package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("station_queue")
public class StationQueue {
    private Long id;
    private Long stationId;
    private Long lineId;
    private String direction;
    private Integer waitingCount;
    private BigDecimal confidence;
    private LocalDateTime lastUpdateTime;
}