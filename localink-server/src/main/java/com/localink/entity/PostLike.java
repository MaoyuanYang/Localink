package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子点赞事实行（M6-B 启用）。uk_post_user 唯一索引兜底一人一赞；
 * 帖子榜 ZSet（KeyManage.POST_LIKE_TOP）由本表事实增量维护、可重算。
 */
@Data
@TableName("lk_post_like")
public class PostLike {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long postId;

    private Long userId;

    private LocalDateTime createTime;
}
