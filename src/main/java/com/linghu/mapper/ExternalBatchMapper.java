package com.linghu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linghu.entity.ExternalBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ExternalBatchMapper extends BaseMapper<ExternalBatch> {

    /**
     * 按品牌ID分页查询批次列表（按创建时间倒序）
     */
    List<ExternalBatch> selectByBrandId(@Param("brandId") Long brandId,
                                        @Param("offset") int offset,
                                        @Param("size") int size);
}
