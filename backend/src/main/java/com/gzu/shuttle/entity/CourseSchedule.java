package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalTime;

@Data
@TableName("course_schedule")
public class CourseSchedule {
    private Long id;
    private String building;
    private LocalTime endTime;
    private Integer studentCount;
    private Long nearbyStationId;
    private BigDecimal conversionRate;
    private Integer weekday;
}