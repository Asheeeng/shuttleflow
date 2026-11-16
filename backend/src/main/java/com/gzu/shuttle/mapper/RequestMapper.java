package com.gzu.shuttle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gzu.shuttle.entity.Request;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RequestMapper extends BaseMapper<Request> {
    @Select("SELECT * FROM `request` WHERE active_key=#{activeKey} AND status='waiting'")
    Request selectActive(@Param("activeKey") String activeKey);

    @Update("UPDATE `request` SET status='cancelled', active_key=NULL, updated_at=CURRENT_TIMESTAMP " +
            "WHERE id=#{id} AND status='waiting'")
    int cancelWaiting(@Param("id") Long id);

    @Update("UPDATE `request` SET status='boarded', active_key=NULL, updated_at=CURRENT_TIMESTAMP " +
            "WHERE station_id=#{stationId} AND line_id=#{lineId} AND direction=#{direction} " +
            "AND status='waiting' ORDER BY created_at ASC LIMIT #{count}")
    int markBoardedAtStation(@Param("stationId") Long stationId, @Param("lineId") Long lineId,
                             @Param("direction") String direction, @Param("count") int count);
}
