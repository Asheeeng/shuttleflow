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

    @Update("UPDATE `request` SET status='boarded', active_key=NULL, boarded_vehicle_id=#{vehicle}, " +
            "trip_id=#{trip}, boarded_at=#{time}, updated_at=#{time} WHERE station_id=#{station} AND line_id=#{line} " +
            "AND direction=#{direction} AND status='waiting' ORDER BY created_at,id LIMIT #{count}")
    int boardForTrip(@Param("station") Long station, @Param("line") Long line, @Param("direction") String direction,
                     @Param("vehicle") Long vehicle, @Param("trip") String trip,
                     @Param("time") java.time.LocalDateTime time, @Param("count") int count);

    @Update("UPDATE `request` SET alighted_at=#{time}, updated_at=#{time} WHERE trip_id=#{trip} " +
            "AND alighted_at IS NULL AND dest_station_id=#{station} ORDER BY boarded_at,id LIMIT #{count}")
    int alightForTrip(@Param("trip") String trip, @Param("station") Long station,
                     @Param("time") java.time.LocalDateTime time, @Param("count") int count);

    @Update("UPDATE `request` SET status='cancelled', active_key=NULL, updated_at=#{time} WHERE id=#{id} AND status='waiting'")
    int cancelAt(@Param("id") Long id, @Param("time") java.time.LocalDateTime time);

    @Update("UPDATE `request` SET status='boarded', active_key=NULL, updated_at=CURRENT_TIMESTAMP " +
            "WHERE station_id=#{stationId} AND line_id=#{lineId} AND direction=#{direction} " +
            "AND status='waiting' ORDER BY created_at ASC LIMIT #{count}")
    int markBoardedAtStation(@Param("stationId") Long stationId, @Param("lineId") Long lineId,
                             @Param("direction") String direction, @Param("count") int count);
}
