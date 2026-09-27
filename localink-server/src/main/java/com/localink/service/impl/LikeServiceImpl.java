package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.api.vo.PostVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.model.ZSetEntry;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.entity.PostLike;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.PostCommentMapper;
import com.localink.mapper.PostLikeMapper;
import com.localink.mapper.PostMapper;
import com.localink.service.LikeService;
import com.localink.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 帖子互动实现（M6-B）："DB 事实源 + ZSet 加速层"——事实行与冗余计数同事务，
 * 榜单 ZINCRBY 走 afterCommit（Redis 不先于提交落笔，漏加只是漂移、可重算）。
 * 重复点赞幂等：防重靠 uk_post_user 唯一索引，catch 只作优雅短路。
 */
@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private static final int MAX_TOP_LIMIT = 50;
    private static final int AUDIT_PASSED = 1;

    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final PostCommentMapper commentMapper;
    private final PostService postService;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    @Transactional
    public int like(Long postId) {
        Post post = requireVisiblePost(postId);
        PostLike like = new PostLike();
        like.setPostId(postId);
        like.setUserId(UserHolder.get().getId());
        try {
            postLikeMapper.insert(like);
        } catch (DuplicateKeyException e) {
            return post.getLiked();
        }
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId)
                .setSql("liked = liked + 1"));
        KeyBuild rankKey = keyBuilder.build(KeyManage.POST_LIKE_TOP);
        TxCallbacks.afterCommit(() ->
                redisCache.zsets().incrementScore(rankKey, String.valueOf(postId), 1));
        return post.getLiked() + 1;
    }

    @Override
    @Transactional
    public int unlike(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "帖子不存在");
        }
        int removed = postLikeMapper.delete(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId)
                .eq(PostLike::getUserId, UserHolder.get().getId()));
        if (removed == 0) {
            return post.getLiked();
        }
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId)
                .gt(Post::getLiked, 0)
                .setSql("liked = liked - 1"));
        KeyBuild rankKey = keyBuilder.build(KeyManage.POST_LIKE_TOP);
        TxCallbacks.afterCommit(() -> {
            // score≤0（含榜单无此 member 被负增量新建）即 ZREM，防僵尸 member 占 TopN 名额
            Double score = redisCache.zsets().incrementScore(rankKey, String.valueOf(postId), -1);
            if (score == null || score <= 0) {
                redisCache.zsets().remove(rankKey, String.valueOf(postId));
            }
        });
        return Math.max(post.getLiked() - 1, 0);
    }

    @Override
    public List<PostVO> top(int limit) {
        int bounded = Math.max(1, Math.min(MAX_TOP_LIMIT, limit));
        Set<ZSetEntry<String>> entries = redisCache.zsets().reverseRangeWithScore(
                keyBuilder.build(KeyManage.POST_LIKE_TOP), 0, bounded - 1, String.class);
        if (entries.isEmpty()) {
            return List.of();
        }
        List<Long> postIds = entries.stream().map(entry -> Long.valueOf(entry.value())).toList();
        return postService.listOrdered(postIds);
    }

    @Override
    public int likeComment(Long commentId) {
        PostComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "评论不存在");
        }
        commentMapper.update(null, new LambdaUpdateWrapper<PostComment>()
                .eq(PostComment::getId, commentId)
                .setSql("liked = liked + 1"));
        return comment.getLiked() + 1;
    }

    private Post requireVisiblePost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != AUDIT_PASSED) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "帖子不存在或未过审");
        }
        return post;
    }
}
