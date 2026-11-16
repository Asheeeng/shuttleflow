package com.gzu.shuttle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gzu.shuttle.entity.ShuttleEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ShuttleEventMapper extends BaseMapper<ShuttleEvent> {
}