package com.gzu.shuttle.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("station")
public class Station {
    private Long id;
    private String name;
    private String shortName;
    private String campus;
    private String qrCode;
    private Integer enabled;
}