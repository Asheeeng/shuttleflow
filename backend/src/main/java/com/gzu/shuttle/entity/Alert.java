package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("alert")
public class Alert {
    private Long id;
    private String level;
    private String type;
    private Long stationId;
    private Long vehicleId;
    private Long lineId;
    private String message;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
}