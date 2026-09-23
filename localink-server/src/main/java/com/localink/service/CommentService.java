package com.localink.service;

import com.localink.api.dto.CommentCreateDTO;
import com.localink.api.vo.CommentVO;
import com.localink.api.vo.PageVO;

/**
 * 帖子评论（M6-A）：两级结构（一级 + 楼中楼）。
 */
public interface CommentService {

    String create(CommentCreateDTO dto);

    /**
     * 删除（仅评论者本人）：删一级级联删其楼中楼，帖子评论计数同步回退。
     */
    void delete(Long commentId);

    /**
     * 帖子的评论分页：一级评论分页，每条携带楼中楼（时间正序）。
     */
    PageVO<CommentVO> pageOfPost(Long postId, long page, long size);
}
