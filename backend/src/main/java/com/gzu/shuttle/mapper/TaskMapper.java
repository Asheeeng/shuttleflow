package com.gzu.shuttle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gzu.shuttle.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
    @Select("SELECT * FROM task WHERE id=#{id} FOR UPDATE")
    Task selectForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM task WHERE dispatch_key=#{dispatchKey} LIMIT 1")
    Task selectByDispatchKey(@Param("dispatchKey") String dispatchKey);

    @Select("SELECT * FROM task WHERE dispatch_key=#{dispatchKey} LIMIT 1 FOR UPDATE")
    Task selectByDispatchKeyForUpdate(@Param("dispatchKey") String dispatchKey);
}
