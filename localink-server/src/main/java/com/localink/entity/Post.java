package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 探店分享帖（M6-A 启用）。审核状态机由 M6-F 驱动；liked/comments 为冗余计数
 * （事实源：lk_post_like / 本表评论行数）；viewed 为浏览快照（M6-E 换 HLL）。
 */
@Data
@TableName("lk_post")
public class Post {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long shopId;

    private String title;

    private String images;

    private String content;

    private Integer liked;

    private Integer comments;

    private Integer viewed;

    /**
     * 0 待审核 / 1 通过 / 2 驳回；Feed 仅展示 1。
     */
    private Integer auditStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
