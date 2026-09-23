package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.PostCreateDTO;
import com.localink.api.vo.PageVO;
import com.localink.api.vo.PostVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.entity.User;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.PostCommentMapper;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 帖子实现（M6-A）：读路径暂为裸 DB——UGC 读多写少的缓存/多级防线留给演进叙事
 * （若 QPS 上来：详情套商户同款旁路缓存，列表走 ES/Feed）。审核状态在 M6-F 前
 * 统一放行（audit=1），DFA 接入后改 0 走异步状态机。
 */
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final int AUDIT_PASSED = 1;
    private static final int MAX_IMAGES = 9;

    private final PostMapper postMapper;
    private final PostCommentMapper commentMapper;
    private final UserMapper userMapper;

    @Override
    public String create(PostCreateDTO dto) {
        Post post = new Post();
        post.setUserId(UserHolder.get().getId());
        post.setShopId(dto.getShopId());
        post.setTitle(dto.getTitle());
        post.setImages(normalizeImages(dto.getImages()));
        post.setContent(dto.getContent());
        post.setLiked(0);
        post.setComments(0);
        post.setViewed(0);
        post.setAuditStatus(AUDIT_PASSED);
        postMapper.insert(post);
        return String.valueOf(post.getId());
    }

    @Override
    @Transactional
    public void delete(Long postId) {
        Post post = requireOwnPost(postId);
        commentMapper.delete(new LambdaQueryWrapper<PostComment>()
                .eq(PostComment::getPostId, postId));
        postMapper.deleteById(post.getId());
    }

    @Override
    public PostVO detail(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != AUDIT_PASSED) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "帖子不存在或未过审");
        }
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId)
                .setSql("viewed = viewed + 1"));
        post.setViewed(post.getViewed() + 1);
        return toVo(List.of(post)).get(0);
    }

    @Override
    public PageVO<PostVO> page(long page, long size, Long shopId) {
        Page<Post> result = postMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Post>()
                        .eq(Post::getAuditStatus, AUDIT_PASSED)
                        .eq(shopId != null, Post::getShopId, shopId)
                        .orderByDesc(Post::getCreateTime));
        return PageVO.of(result.getTotal(), toVo(result.getRecords()));
    }

    private Post requireOwnPost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "帖子不存在");
        }
        if (!post.getUserId().equals(UserHolder.get().getId())) {
            throw new LocalinkException(BaseCode.FORBIDDEN, "只能删除自己的帖子");
        }
        return post;
    }

    private List<PostVO> toVo(List<Post> posts) {
        Map<Long, String> nickNames = nickNamesOf(posts.stream().map(Post::getUserId).toList());
        return posts.stream().map(post -> {
            PostVO vo = new PostVO();
            vo.setId(post.getId());
            vo.setUserId(post.getUserId());
            vo.setNickName(nickNames.getOrDefault(post.getUserId(), "匿名用户"));
            vo.setShopId(post.getShopId());
            vo.setTitle(post.getTitle());
            vo.setImages(post.getImages() == null || post.getImages().isBlank()
                    ? List.of() : Arrays.asList(post.getImages().split(",")));
            vo.setContent(post.getContent());
            vo.setLiked(post.getLiked());
            vo.setComments(post.getComments());
            vo.setViewed(post.getViewed());
            vo.setCreateTime(post.getCreateTime());
            return vo;
        }).collect(Collectors.toList());
    }

    Map<Long, String> nickNamesOf(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getNickName() == null || user.getNickName().isBlank()
                                ? "用户" + String.valueOf(user.getId()).substring(String.valueOf(user.getId()).length() - 4)
                                : user.getNickName(),
                        (a, b) -> a));
    }

    private String normalizeImages(String images) {
        if (images == null || images.isBlank()) {
            return "";
        }
        String[] parts = images.split(",");
        List<String> valid = Arrays.stream(parts).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        if (valid.size() > MAX_IMAGES) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "图片最多 " + MAX_IMAGES + " 张");
        }
        return String.join(",", valid);
    }
}
