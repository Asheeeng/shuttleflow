package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalTime;

@Data
@TableName("line")
public class Line {
    private Long id;
    private String lineNo;
    private String name;
    private Integer capacity;
    private LocalTime firstTime;
    private LocalTime lastTime;
    private Integer peakIntervalMin;
    private Integer offIntervalMin;
    private Integer enabled;
}