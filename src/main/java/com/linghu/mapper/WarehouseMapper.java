package com.linghu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linghu.entity.Warehouse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WarehouseMapper extends BaseMapper<Warehouse> {

    /**
     * 查询附近指定距离内的仓库（Haversine 公式，单位：km）
     */
    @Select("SELECT *, " +
            "(6371 * ACOS(COS(RADIANS(#{lat})) * COS(RADIANS(lat)) * COS(RADIANS(lng) - RADIANS(#{lng})) + SIN(RADIANS(#{lat})) * SIN(RADIANS(lat)))) AS distance " +
            "FROM warehouse " +
            "WHERE deleted = 0 AND status = 1 AND audit_status = 'APPROVED' " +
            "HAVING distance < #{maxDistanceKm} " +
            "ORDER BY distance ASC")
    List<Warehouse> findNearby(@Param("lat") Double lat, @Param("lng") Double lng, @Param("maxDistanceKm") Double maxDistanceKm);
}
