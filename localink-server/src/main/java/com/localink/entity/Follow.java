package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 关注关系（M6-B 启用）：user_id 关注者（发起方）、follow_user_id 被关注者。
 * uk_user_follow 防重复关注；粉丝明细反查走 idx_follow_user_id（粉丝侧不建 Redis Set）。
 */
@Data
@TableName("lk_follow")
public class Follow {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long followUserId;

    private LocalDateTime createTime;
}
