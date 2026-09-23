package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.CommentCreateDTO;
import com.localink.api.vo.CommentVO;
import com.localink.api.vo.PageVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.PostCommentMapper;
import com.localink.mapper.PostMapper;
import com.localink.service.CommentService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论实现（M6-A）：两级结构——楼中楼的 parent 必须是一级评论（两级上限），
 * reply 指向被回复的评论（昵称回填"回复@谁"）。帖子冗余计数 comments 与评论同事务增减。
 */
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private static final int AUDIT_PASSED = 1;

    private final PostCommentMapper commentMapper;
    private final PostMapper postMapper;
    @Lazy
    private final PostServiceImpl postService;

    @Override
    @Transactional
    public String create(CommentCreateDTO dto) {
        Post post = postMapper.selectById(dto.getPostId());
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != AUDIT_PASSED) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "帖子不存在或未过审");
        }
        validateHierarchy(dto);
        PostComment comment = new PostComment();
        comment.setPostId(dto.getPostId());
        comment.setUserId(UserHolder.get().getId());
        comment.setParentId(dto.getParentId());
        comment.setReplyId(dto.getReplyId());
        comment.setContent(dto.getContent());
        comment.setLiked(0);
        comment.setAuditStatus(AUDIT_PASSED);
        commentMapper.insert(comment);
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, dto.getPostId())
                .setSql("comments = comments + 1"));
        return String.valueOf(comment.getId());
    }

    @Override
    @Transactional
    public void delete(Long commentId) {
        PostComment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "评论不存在");
        }
        if (!comment.getUserId().equals(UserHolder.get().getId())) {
            throw new LocalinkException(BaseCode.FORBIDDEN, "只能删除自己的评论");
        }
        int removed = 1;
        if (comment.getParentId() == 0) {
            Long children = commentMapper.selectCount(new LambdaQueryWrapper<PostComment>()
                    .eq(PostComment::getParentId, commentId));
            commentMapper.delete(new LambdaQueryWrapper<PostComment>()
                    .eq(PostComment::getParentId, commentId));
            removed += children;
        }
        commentMapper.deleteById(commentId);
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, comment.getPostId())
                .setSql("comments = comments - " + removed));
    }

    @Override
    public PageVO<CommentVO> pageOfPost(Long postId, long page, long size) {
        Page<PostComment> top = commentMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<PostComment>()
                        .eq(PostComment::getPostId, postId)
                        .eq(PostComment::getParentId, 0)
                        .orderByDesc(PostComment::getCreateTime));
        List<PostComment> children = commentMapper.selectList(
                new LambdaQueryWrapper<PostComment>()
                        .eq(PostComment::getPostId, postId)
                        .ne(PostComment::getParentId, 0)
                        .orderByAsc(PostComment::getCreateTime));
        Map<Long, List<PostComment>> byParent = children.stream()
                .collect(Collectors.groupingBy(PostComment::getParentId));

        List<PostComment> all = new ArrayList<>(top.getRecords());
        all.addAll(children);
        Map<Long, String> nickNames = postService.nickNamesOf(
                all.stream().map(PostComment::getUserId).distinct().toList());
        Map<Long, PostComment> byId = all.stream()
                .collect(Collectors.toMap(PostComment::getId, c -> c, (a, b) -> a));

        List<CommentVO> records = top.getRecords().stream()
                .map(c -> toVo(c, byParent.getOrDefault(c.getId(), List.of()), nickNames, byId))
                .collect(Collectors.toList());
        return PageVO.of(top.getTotal(), records);
    }

    /**
     * 两级上限校验：parent 必须存在、同帖、且自身是一级评论；reply 必须存在且同帖。
     */
    private void validateHierarchy(CommentCreateDTO dto) {
        if (dto.getParentId() != 0) {
            PostComment parent = commentMapper.selectById(dto.getParentId());
            if (parent == null || !parent.getPostId().equals(dto.getPostId())) {
                throw new LocalinkException(BaseCode.PARAM_ERROR, "被回复的一级评论不存在");
            }
            if (parent.getParentId() != 0) {
                throw new LocalinkException(BaseCode.PARAM_ERROR, "评论最多两级：楼中楼需挂在一级评论下");
            }
        }
        if (dto.getReplyId() != 0) {
            PostComment reply = commentMapper.selectById(dto.getReplyId());
            if (reply == null || !reply.getPostId().equals(dto.getPostId())) {
                throw new LocalinkException(BaseCode.PARAM_ERROR, "被回复的评论不存在");
            }
        }
    }

    private CommentVO toVo(PostComment comment, List<PostComment> children,
                           Map<Long, String> nickNames, Map<Long, PostComment> byId) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setPostId(comment.getPostId());
        vo.setUserId(comment.getUserId());
        vo.setNickName(nickNames.getOrDefault(comment.getUserId(), "匿名用户"));
        vo.setParentId(comment.getParentId());
        vo.setReplyId(comment.getReplyId());
        if (comment.getReplyId() != 0 && byId.containsKey(comment.getReplyId())) {
            PostComment reply = byId.get(comment.getReplyId());
            vo.setReplyNickName(nickNames.getOrDefault(reply.getUserId(), "匿名用户"));
        }
        vo.setContent(comment.getContent());
        vo.setLiked(comment.getLiked());
        vo.setCreateTime(comment.getCreateTime());
        if (!children.isEmpty()) {
            vo.setChildren(children.stream()
                    .map(child -> toVo(child, List.of(), nickNames, byId))
                    .collect(Collectors.toList()));
        }
        return vo;
    }
}
