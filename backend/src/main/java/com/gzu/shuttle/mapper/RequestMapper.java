package com.gzu.shuttle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gzu.shuttle.entity.Request;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RequestMapper extends BaseMapper<Request> {
}