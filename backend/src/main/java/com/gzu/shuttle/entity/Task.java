package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.fasterxml.jackson.annotation.JsonIgnore;
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
    private String direction;
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long vehicleId;
    private Integer priority;
    private String status;
    @JsonIgnore
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String dispatchKey;
    private String reasonCode;
    private BigDecimal confidence;
    private String suggestion;
    private LocalDateTime assignedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
