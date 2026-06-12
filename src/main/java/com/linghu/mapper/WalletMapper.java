package com.linghu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linghu.entity.Wallet;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface WalletMapper extends BaseMapper<Wallet> {

    /**
     * CAS 安全更新余额：amount 为正则加钱，为负则减钱；
     * WHERE 条件保证余额不会变为负数（balance + amount >= 0）
     *
     * @param userId 用户ID
     * @param amount 变动金额（正=增加，负=减少）
     * @return 受影响行数（0 表示余额不足或记录不存在）
     */
    @Update("UPDATE wallet SET balance = balance + #{amount}, update_time = NOW() " +
            "WHERE user_id = #{userId} AND balance + #{amount} >= 0")
    int updateBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);
}
