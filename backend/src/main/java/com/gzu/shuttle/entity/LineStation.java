package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("line_station")
public class LineStation {
    private Long id;
    private Long lineId;
    private Long stationId;
    private String direction;
    private Integer seq;
    private Integer distanceM;
    private Integer travelSec;
}