package com.localink.api.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论视图（M6-A）：一级评论分页载体，楼中楼挂 children；replyNickName 为被回复者昵称。
 */
@Data
public class CommentVO {

    private Long id;

    private Long postId;

    private Long userId;

    private String nickName;

    private Long parentId;

    private Long replyId;

    private String replyNickName;

    private String content;

    private Integer liked;

    private LocalDateTime createTime;

    /**
     * 楼中楼（仅一级评论携带，按时间正序）。
     */
    private List<CommentVO> children;
}
