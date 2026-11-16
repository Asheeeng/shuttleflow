package com.gzu.shuttle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gzu.shuttle.entity.Vehicle;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface VehicleMapper extends BaseMapper<Vehicle> {
}