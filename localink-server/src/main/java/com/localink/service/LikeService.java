package com.localink.service;

import com.localink.api.vo.PostVO;

import java.util.List;

/**
 * 帖子互动（M6-B）：点赞/取消（幂等）、点赞榜 TopN、评论点赞（仅计数）。
 */
public interface LikeService {

    /**
     * 点赞：事实行 + 帖子冗余计数同事务，事务提交后榜单 ZINCRBY；重复点赞幂等返回当前值。
     *
     * @return 更新后的帖子点赞数
     */
    int like(Long postId);

    /**
     * 取消点赞：删事实行 + 条件递减计数，事务提交后榜单 ZINCRBY -1（score≤0 即 ZREM）；未赞过幂等。
     *
     * @return 更新后的帖子点赞数
     */
    int unlike(Long postId);

    /**
     * 点赞榜 TopN：ZREVRANGE 取 postId 后按榜序回填帖子信息（未过审/已删帖跳过）。
     */
    List<PostVO> top(int limit);

    /**
     * 评论点赞：仅计数不入榜（无事实表，database.md 4.8 既有决策）。
     *
     * @return 更新后的评论点赞数
     */
    int likeComment(Long commentId);
}
