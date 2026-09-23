package com.localink.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 评论入参（M6-A，两级结构）：parentId=0 一级评论；楼中楼 parentId 指向一级评论、
 * replyId 指向被回复的楼中楼（0=直接回复楼主）。
 */
@Data
public class CommentCreateDTO {

    @NotNull(message = "帖子不能为空")
    private Long postId;

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 512, message = "评论最长 512 字")
    private String content;

    private long parentId = 0;

    private long replyId = 0;
}
