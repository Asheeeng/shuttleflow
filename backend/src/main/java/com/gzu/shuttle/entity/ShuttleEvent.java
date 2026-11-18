package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("event")
public class ShuttleEvent {
    private Long id;
    private String eventNo;
    private String type;
    private String source;
    private Long vehicleId;
    private Long stationId;
    private Long lineId;
    private String direction;
    private String tripId;
    private Integer count;
    private Integer afterLoad;
    private Integer waitingAtDeparture;
    private BigDecimal confidence;
    private LocalDateTime eventTime;
    private LocalDateTime reportTime;
    private Integer processed;
}