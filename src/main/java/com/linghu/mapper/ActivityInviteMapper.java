package com.linghu.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linghu.entity.ActivityInvite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ActivityInviteMapper extends BaseMapper<ActivityInvite> {

    /** 原子递增活动已用名额 */
    @Update("UPDATE activity SET used_quota = used_quota + 1 WHERE id = #{activityId} AND (total_quota = 0 OR used_quota < total_quota)")
    int incrementUsedQuota(Long activityId);
}
