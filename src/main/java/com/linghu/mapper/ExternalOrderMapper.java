package com.linghu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linghu.entity.ExternalOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ExternalOrderMapper extends BaseMapper<ExternalOrder> {

    /**
     * 按品牌ID和状态分页查询外单列表（status为null时查全部）
     */
    List<ExternalOrder> selectByBrandIdAndStatus(@Param("brandId") Long brandId,
                                                 @Param("status") Integer status,
                                                 @Param("offset") int offset,
                                                 @Param("size") int size);

    /**
     * 按仓库ID和状态查询外单列表（status为null时查全部）
     */
    List<ExternalOrder> selectByWarehouseIdAndStatus(@Param("warehouseId") Long warehouseId,
                                                     @Param("status") Integer status);
}
