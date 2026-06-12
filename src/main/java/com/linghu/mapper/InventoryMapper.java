package com.linghu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linghu.entity.Inventory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface InventoryMapper extends BaseMapper<Inventory> {

    /**
     * 锁定库存（原子操作）
     */
    @Update("UPDATE inventory SET locked_quantity = locked_quantity + #{quantity} " +
            "WHERE warehouse_id = #{warehouseId} AND product_id = #{productId} " +
            "AND deleted = 0 AND quantity - locked_quantity >= #{quantity}")
    int lockInventory(@Param("warehouseId") Long warehouseId,
                      @Param("productId") Long productId,
                      @Param("quantity") Integer quantity);

    /**
     * 释放锁定库存（取消订单时）
     */
    @Update("UPDATE inventory SET locked_quantity = locked_quantity - #{quantity} " +
            "WHERE warehouse_id = #{warehouseId} AND product_id = #{productId} " +
            "AND deleted = 0 AND locked_quantity >= #{quantity}")
    int unlockInventory(@Param("warehouseId") Long warehouseId,
                        @Param("productId") Long productId,
                        @Param("quantity") Integer quantity);

    /**
     * 完成发货：扣减 quantity 并释放 locked_quantity
     */
    @Update("UPDATE inventory SET quantity = quantity - #{quantity}, locked_quantity = locked_quantity - #{quantity} " +
            "WHERE warehouse_id = #{warehouseId} AND product_id = #{productId} " +
            "AND deleted = 0 AND locked_quantity >= #{quantity}")
    int deductInventory(@Param("warehouseId") Long warehouseId,
                        @Param("productId") Long productId,
                        @Param("quantity") Integer quantity);

    /**
     * 入库：增加库存
     */
    @Update("UPDATE inventory SET quantity = quantity + #{quantity}, last_inbound_at = NOW() " +
            "WHERE warehouse_id = #{warehouseId} AND product_id = #{productId} AND deleted = 0")
    int addInventory(@Param("warehouseId") Long warehouseId,
                     @Param("productId") Long productId,
                     @Param("quantity") Integer quantity);
}
