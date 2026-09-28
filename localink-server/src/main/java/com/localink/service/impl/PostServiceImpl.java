package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.PostCreateDTO;
import com.localink.api.dto.UserDTO;
import com.localink.api.vo.PageVO;
import com.localink.api.vo.PostVO;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.entity.PostComment;
import com.localink.entity.PostLike;
import com.localink.entity.User;
import com.localink.event.PostCreatedEvent;
import com.localink.event.PostDeletedEvent;
import com.localink.framework.dfa.SensitiveWordDFA;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.PostCommentMapper;
import com.localink.mapper.PostLikeMapper;
import com.localink.mq.MessageProducer;
import com.localink.mq.MqTopics;
import com.localink.mq.PostAuditMessage;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final PostLikeMapper postLikeMapper;
    private final UserMapper userMapper;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final SensitiveWordDFA sensitiveWordDfa;
    private final MessageProducer messageProducer;

    @Override
    @Transactional
    public String create(PostCreateDTO dto) {
        // 同步初筛（M6-F）：确定性已知风险挡在落库前——显性词库命中直接拒发帖（先审后发档）
        if (sensitiveWordDfa.contains(dto.getTitle() + dto.getContent())) {
            throw new LocalinkException(BaseCode.POST_AUDIT_REJECTED);
        }
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
        // 发帖即事实：Feed 收件箱（进程内投影）与 ES 同步（跨进程 MQ 投影，M6-D）订阅该事件；
        // 订阅方一律 AFTER_COMMIT——事务内发布，提交后才消费，回滚不留幻影
        eventPublisher.publishEvent(new PostCreatedEvent(post.getId(), post.getUserId()));
        // 先发后审档（M6-F）：audit=1 先放行立即可见，提交后投异步复审——隐性词库命中再收回
        Long postId = post.getId();
        TxCallbacks.afterCommit(() -> messageProducer.sendAsync(
                MqTopics.POST_AUDIT, String.valueOf(postId), new PostAuditMessage(postId)));
        return String.valueOf(post.getId());
    }

    @Override
    @Transactional
    public void delete(Long postId) {
        Post post = requireOwnPost(postId);
        commentMapper.delete(new LambdaQueryWrapper<PostComment>()
                .eq(PostComment::getPostId, postId));
        postLikeMapper.delete(new LambdaQueryWrapper<PostLike>()
                .eq(PostLike::getPostId, postId));
        postMapper.deleteById(post.getId());
        // ZREM 与删行同事务段执行：残局（帖在榜无 / 帖无榜有）由 top 按现存帖回填 + 事实源重算兜底
        redisCache.zsets().remove(keyBuilder.build(KeyManage.POST_LIKE_TOP), String.valueOf(postId));
        // M6-E：热榜快照与浏览 UV 一并清理（对齐级联矩阵；UV 事实随帖消亡，保留无意义）
        redisCache.zsets().remove(keyBuilder.build(KeyManage.POST_HOT_TOP), String.valueOf(postId));
        redisCache.delete(keyBuilder.build(KeyManage.POST_UV, postId));
        eventPublisher.publishEvent(new PostDeletedEvent(postId));
    }

    @Override
    public PostVO detail(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != AUDIT_PASSED) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "帖子不存在或未过审");
        }
        // M6-E：朴素 viewed+1 退役——viewed 语义收敛为 UV 快照（HotRankJob 定时 PFCOUNT 回写）；
        // 游客（未登录）不计 UV：member 无稳定标识，IP 口径代理下不准且有伪造面
        UserDTO me = UserHolder.get();
        if (me != null) {
            redisCache.hyperloglogs().add(
                    keyBuilder.build(KeyManage.POST_UV, postId), String.valueOf(me.getId()));
        }
        PostVO vo = toVo(List.of(post)).get(0);
        if (me != null) {
            vo.setMeLiked(postLikeMapper.selectCount(new LambdaQueryWrapper<PostLike>()
                    .eq(PostLike::getPostId, postId)
                    .eq(PostLike::getUserId, me.getId())) > 0);
        }
        return vo;
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

    @Override
    public List<PostVO> listOrdered(List<Long> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<Long, Post> byId = postMapper.selectBatchIds(orderedIds).stream()
                .filter(post -> post.getAuditStatus() != null && post.getAuditStatus() == AUDIT_PASSED)
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        return toVo(orderedIds.stream().map(byId::get).filter(Objects::nonNull).toList());
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
