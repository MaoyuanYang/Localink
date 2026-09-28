package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.api.vo.PostVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.config.HotProperties;
import com.localink.constant.KeyManage;
import com.localink.entity.Post;
import com.localink.mapper.PostMapper;
import com.localink.service.HotRankService;
import com.localink.service.PostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热榜实现（M6-E）：全量重算模型——分数 = (liked×w_like + comments×w_comment + uv×w_view)
 * × e^(-λΔt)，λ=ln2/半衰期（衰减是时间函数非事件函数：同一事实快照+同一时刻重算必同值）。
 * 与增量 ZINCRBY 累计方案的对比：免三处行为挂点/免取消删除回退/无漂移——代价是任务端
 * 多读几次事实源，候选集有界可接受（取舍详见任务卡 §3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(HotProperties.class)
public class HotRankServiceImpl implements HotRankService {

    private static final int MAX_LIMIT = 50;
    private static final int AUDIT_PASSED = 1;

    private final PostMapper postMapper;
    private final PostService postService;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;
    private final HotProperties properties;

    @Override
    public long runOnce() {
        KeyBuild rankKey = keyBuilder.build(KeyManage.POST_HOT_TOP);
        // 候选集 = 近期新帖（idx_create_time）∪ 现役榜帖（防窗口边界帖瞬断）
        Map<Long, Post> candidates = new HashMap<>();
        LocalDateTime since = LocalDateTime.now().minusDays(properties.getRecencyDays());
        for (Post post : postMapper.selectList(new LambdaQueryWrapper<Post>()
                .ge(Post::getCreateTime, since)
                .eq(Post::getAuditStatus, AUDIT_PASSED))) {
            candidates.put(post.getId(), post);
        }
        Set<String> currentMembers = redisCache.zsets().range(rankKey, 0, -1, String.class);
        List<Long> currentIds = currentMembers.stream().map(Long::valueOf).toList();
        if (!currentIds.isEmpty()) {
            // 空列表会生成 IN () 非法 SQL——首轮快照（榜为空）必须跳过
            for (Post post : postMapper.selectBatchIds(currentIds)) {
                if (post.getAuditStatus() != null && post.getAuditStatus() == AUDIT_PASSED) {
                    candidates.putIfAbsent(post.getId(), post);
                }
            }
        }

        long now = System.currentTimeMillis();
        double lambda = Math.log(2) / properties.getHalfLifeHours();
        HotProperties.Weights w = properties.getWeights();
        List<String> stale = new ArrayList<>(currentMembers);
        stale.removeAll(candidates.keySet().stream().map(String::valueOf).toList());

        long written = 0;
        for (Post post : candidates.values()) {
            long uv = redisCache.hyperloglogs().count(keyBuilder.build(KeyManage.POST_UV, post.getId()));
            writeBackViewed(post, uv);
            double raw = post.getLiked() * w.getLike()
                    + post.getComments() * w.getComment()
                    + uv * w.getView();
            double hours = (now - post.getCreateTime()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) / 3_600_000.0;
            double score = raw * Math.exp(-lambda * hours);
            if (score > 0) {
                redisCache.zsets().add(rankKey, String.valueOf(post.getId()), score);
                written++;
            } else {
                stale.add(String.valueOf(post.getId()));
            }
        }
        if (!stale.isEmpty()) {
            redisCache.zsets().remove(rankKey, stale.toArray());
        }
        log.info("热榜快照完成, candidates={}, written={}, removed={}", candidates.size(), written, stale.size());
        return written;
    }

    @Override
    public List<PostVO> top(int limit) {
        int bounded = Math.max(1, Math.min(MAX_LIMIT, limit));
        Set<String> ids = redisCache.zsets().reverseRange(
                keyBuilder.build(KeyManage.POST_HOT_TOP), 0, bounded - 1, String.class);
        return postService.listOrdered(ids.stream().map(Long::valueOf).toList());
    }

    /**
     * UV 回写 viewed（该列语义=UV 快照）：仅在有变化时落笔，避免每轮全候选集写放大。
     */
    private void writeBackViewed(Post post, long uv) {
        if (post.getViewed() == null || post.getViewed() != uv) {
            postMapper.update(null, new LambdaUpdateWrapper<Post>()
                    .eq(Post::getId, post.getId())
                    .set(Post::getViewed, (int) Math.min(uv, Integer.MAX_VALUE)));
        }
    }
}
