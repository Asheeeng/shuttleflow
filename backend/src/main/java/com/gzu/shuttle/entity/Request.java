package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("request")
public class Request {
    private Long id;
    private Long passengerId;
    private Long stationId;
    private Long lineId;
    private String direction;
    private Long destStationId;
    private Long boardedVehicleId;
    private String tripId;
    private LocalDateTime boardedAt;
    private LocalDateTime alightedAt;
    private String type;
    private String status;
    @JsonIgnore
    private String activeKey;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
