package com.gzu.shuttle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gzu.shuttle.entity.Vehicle;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VehicleMapper extends BaseMapper<Vehicle> {
    @Select("SELECT * FROM vehicle WHERE id=#{id} FOR UPDATE")
    Vehicle selectForUpdate(@Param("id") Long id);
}
