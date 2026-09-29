package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.localink.api.dto.UserDTO;
import com.localink.api.vo.PostVO;
import com.localink.api.vo.ScrollVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.cache.model.ZSetEntry;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.config.FeedProperties;
import com.localink.constant.KeyManage;
import com.localink.entity.Follow;
import com.localink.entity.Post;
import com.localink.entity.User;
import com.localink.event.PostCreatedEvent;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.FollowMapper;
import com.localink.mapper.PostMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.FeedService;
import com.localink.service.PostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关注流实现（M6-C）。推模式：订阅 PostCreatedEvent，反查粉丝逐个 ZADD 收件箱（大 V 不推，
 * 写扩散上界被阈值封顶；粉丝量上界内一次查齐）；收件箱是派生投影——推送失败不阻塞发帖事实。
 * 读端归并：收件箱页（推）∪ 我关注的大 V 帖页（拉，DB create_time 倒序+同公式换算 score），
 * 按 score 归并去重，回填后按当前关注集合过滤（取关即时生效）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(FeedProperties.class)
public class FeedServiceImpl implements FeedService {

    /**
     * score 位账：时间位毫秒 << 12 | 雪花 postId 低 12 位（恰为雪花序列位，同毫秒内唯一 →
     * score 全局唯一，游标 exclusive 翻页不重不漏）。41+12=53 位在 double 精确整数范围内。
     */
    private static final int SEQ_BITS = 12;
    private static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int AUDIT_PASSED = 1;

    private final FollowMapper followMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final PostService postService;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final FeedProperties feedProperties;

    // ===== 推模式 =====

    /**
     * 发帖事件监听：AFTER_COMMIT——create 自 M6-D 起有事务边界，提交后才推送（回滚不留幽灵
     * 收件箱条目）。同步推送（演示量级可接受；演进为 Kafka 异步+pipeline 批量）。
     * 推送失败只告警不抛——收件箱是可重建的派生视图，不能阻塞发帖事实。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostCreated(PostCreatedEvent event) {
        try {
            pushToFans(event.postId(), event.authorId());
        } catch (Exception e) {
            log.warn("Feed 推送失败, postId={}, authorId={}: {}", event.postId(), event.authorId(), e.getMessage());
        }
    }

    private void pushToFans(Long postId, Long authorId) {
        User author = userMapper.selectById(authorId);
        if (author == null || author.getFans() == null
                || author.getFans() >= feedProperties.getBigVFansThreshold()) {
            return;
        }
        // 粉丝量上界=大 V 阈值（否则已 return），一次查齐（idx_follow_user_id）
        List<Follow> fans = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getFollowUserId, authorId));
        if (fans.isEmpty()) {
            return;
        }
        double score = buildScore(System.currentTimeMillis(), postId);
        for (Follow fan : fans) {
            KeyBuild inbox = keyBuilder.build(KeyManage.USER_FEED, fan.getUserId());
            redisCache.zsets().add(inbox, String.valueOf(postId), score);
            trimInbox(inbox);
        }
    }

    private void trimInbox(KeyBuild inbox) {
        while (redisCache.zsets().size(inbox) > feedProperties.getInboxMaxSize()) {
            if (redisCache.zsets().popMin(inbox, String.class) == null) {
                break;
            }
        }
    }

    // ===== 读端归并（推挽结合） =====

    @Override
    public ScrollVO<PostVO> feed(Long lastScore, int size) {
        UserDTO me = UserHolder.get();
        if (me == null) {
            throw new LocalinkException(BaseCode.UNAUTHORIZED, "请先登录");
        }
        int bounded = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        Set<Long> followees = redisCache.sets().members(
                        keyBuilder.build(KeyManage.USER_FOLLOWEE, me.getId()), String.class).stream()
                .map(Long::valueOf).collect(Collectors.toSet());
        if (followees.isEmpty()) {
            // Redis 丢失/驱逐兜底（B-8）：DB 事实源回查并写穿回缓存，Feed 不因缓存丢失永久空转
            List<Follow> rows = followMapper.selectList(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, me.getId()));
            if (rows.isEmpty()) {
                return ScrollVO.of(List.of(), null);
            }
            followees = rows.stream().map(Follow::getFollowUserId).collect(Collectors.toSet());
            redisCache.sets().add(keyBuilder.build(KeyManage.USER_FOLLOWEE, me.getId()),
                    followees.stream().map(String::valueOf).toArray());
        }

        // 终态副本：followees 在兜底分支被重赋值，lambda 引用需 effectively-final
        Set<Long> effectiveFollowees = followees;
        // 两路候选均按 score 倒序，归并去重（防"先推后变大 V"同帖两路出现）取前 size 条
        Map<Long, Double> merged = new LinkedHashMap<>();
        mergeEntries(merged, inboxPage(me.getId(), lastScore, bounded));
        mergeEntries(merged, bigVPage(followees, lastScore, bounded));
        List<Map.Entry<Long, Double>> top = merged.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(bounded)
                .toList();

        List<PostVO> records = postService.listOrdered(top.stream().map(Map.Entry::getKey).toList())
                .stream()
                .filter(vo -> effectiveFollowees.contains(vo.getUserId()))
                .toList();
        // cursor 取归并结果（回填过滤前）：被过滤帖不阻塞翻页；不足 size 条=两路候选耗尽，到底
        Long nextCursor = top.size() < bounded ? null : top.get(top.size() - 1).getValue().longValue();
        return ScrollVO.of(records, nextCursor);
    }

    private Set<ZSetEntry<String>> inboxPage(Long userId, Long lastScore, int size) {
        // exclusive 游标：翻页取"更老"的内容——上界=lastScore-1（整数 score，-1 即排除已服务的本条）
        double max = lastScore == null ? Double.POSITIVE_INFINITY : lastScore - 1;
        return redisCache.zsets().reverseRangeByScoreWithScore(
                keyBuilder.build(KeyManage.USER_FEED, userId), Double.NEGATIVE_INFINITY, max, 0, size, String.class);
    }

    private List<Map.Entry<Long, Double>> bigVPage(Set<Long> followees, Long lastScore, int size) {
        List<Long> bigVIds = userMapper.selectBatchIds(followees).stream()
                .filter(user -> user.getFans() != null
                        && user.getFans() >= feedProperties.getBigVFansThreshold())
                .map(User::getId)
                .toList();
        if (bigVIds.isEmpty()) {
            return List.of();
        }
        long lastMillis = lastScore == null ? System.currentTimeMillis() : lastScore >> SEQ_BITS;
        LocalDateTime bound = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastMillis), ZoneId.systemDefault());
        // DB datetime 秒级精度：查 create_time <= lastMillis（含同秒）后按 score 严格小于游标过滤——
        // 同秒中 score 高于游标的属上一页已服务内容。score 时间位来自 DB 秒（同秒内靠 id 低 12 位
        // 区分），与推路径的应用毫秒同构
        // 缓冲 size*2：DB 只按 (create_time,id) 排序，同秒并列时与 score 序（低 12 位）不一致，
        // 缓冲消解页边界截断；归并侧全局按 score 排序取前 size，多取的行自然淘汰
        return postMapper.selectPage(new Page<>(1, size * 2), new LambdaQueryWrapper<Post>()
                        .in(Post::getUserId, bigVIds)
                        .eq(Post::getAuditStatus, AUDIT_PASSED)
                        .le(Post::getCreateTime, bound)
                        .orderByDesc(Post::getCreateTime)
                        .orderByDesc(Post::getId))
                .getRecords().stream()
                .map(post -> Map.entry(post.getId(), (Double) (double) scoreOf(post)))
                .filter(entry -> lastScore == null || entry.getValue() < lastScore)
                .collect(Collectors.toList());
    }

    private void mergeEntries(Map<Long, Double> merged, Set<ZSetEntry<String>> entries) {
        for (ZSetEntry<String> entry : entries) {
            merged.putIfAbsent(Long.valueOf(entry.value()), entry.score());
        }
    }

    private void mergeEntries(Map<Long, Double> merged, List<Map.Entry<Long, Double>> entries) {
        for (Map.Entry<Long, Double> entry : entries) {
            merged.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    static long buildScore(long millis, long postId) {
        return (millis << SEQ_BITS) | (postId & SEQ_MASK);
    }

    private long scoreOf(Post post) {
        // 拉路径时间位取"所在秒的末毫秒"（ceiling）：同一帖推路径 score（真实毫秒）恒不大于
        // 本值——经收件箱已服务过的游标，拉路径不会再放行同一帖（防"先推后变大 V"跨页重复）。
        // 同秒并列帖在归并侧以插入序（DB 按 id 倒序）保持确定；代价是游标落入某秒中段时该秒内
        // 更晚的未推帖可能被跳过——概率与影响都远小于跨页重复，从头翻页不受影响
        long secMillis = post.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return buildScore(secMillis + 999, post.getId());
    }
}
