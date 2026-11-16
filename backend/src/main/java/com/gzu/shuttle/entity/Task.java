package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("task")
public class Task {
    private Long id;
    private String taskNo;
    private String type;
    private Long lineId;
    private Long stationId;
    private Long vehicleId;
    private Integer priority;
    private String status;
    private String reasonCode;
    private BigDecimal confidence;
    private String suggestion;
    private LocalDateTime assignedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}