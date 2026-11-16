package com.gzu.shuttle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gzu.shuttle.entity.StationQueue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StationQueueMapper extends BaseMapper<StationQueue> {
    @Select("SELECT * FROM station_queue WHERE station_id=#{stationId} AND line_id=#{lineId} " +
            "AND direction=#{direction} FOR UPDATE")
    StationQueue selectForUpdate(@Param("stationId") Long stationId, @Param("lineId") Long lineId,
                                 @Param("direction") String direction);

    @Insert("INSERT INTO station_queue (station_id,line_id,direction,waiting_count,confidence,last_update_time) " +
            "VALUES (#{stationId},#{lineId},#{direction},GREATEST(0,#{waitingCount}),#{confidence},CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE waiting_count=GREATEST(0,#{waitingCount}), " +
            "confidence=VALUES(confidence),last_update_time=CURRENT_TIMESTAMP")
    int setAbsolute(@Param("stationId") Long stationId, @Param("lineId") Long lineId,
                    @Param("direction") String direction, @Param("waitingCount") int waitingCount,
                    @Param("confidence") java.math.BigDecimal confidence);

    @Insert("INSERT INTO station_queue (station_id,line_id,direction,waiting_count,confidence,last_update_time) " +
            "VALUES (#{stationId},#{lineId},#{direction},GREATEST(0,#{delta}),#{confidence},CURRENT_TIMESTAMP) " +
            "ON DUPLICATE KEY UPDATE waiting_count=GREATEST(0,waiting_count + #{delta}), " +
            "confidence=VALUES(confidence),last_update_time=CURRENT_TIMESTAMP")
    int applyDelta(@Param("stationId") Long stationId, @Param("lineId") Long lineId,
                   @Param("direction") String direction, @Param("delta") int delta,
                   @Param("confidence") java.math.BigDecimal confidence);
}
