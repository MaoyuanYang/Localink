package com.localink.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 帖子评论（M6-A 启用）：两级结构——一级 parent_id=0；楼中楼 parent_id 指向一级评论、
 * reply_id 指向被回复的评论。
 */
@Data
@TableName("lk_post_comment")
public class PostComment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long postId;

    private Long userId;

    private Long parentId;

    private Long replyId;

    private String content;

    private Integer liked;

    /**
     * 同帖子审核状态机。
     */
    private Integer auditStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
