package com.localink.service;

import com.localink.api.vo.PostVO;

import java.util.List;

/**
 * 帖子热榜（M6-E）：定时全量重算快照（候选集=近期新帖∪现役榜帖，分数=行为加权×e^(-λΔt)）
 * 与热榜读接口。事实源=liked/comments（lk_post 冗余计数）+ 浏览 UV（HyperLogLog），
 * 写端零行为挂点——任何时刻重算都收敛到同一结果。
 */
public interface HotRankService {

    /**
     * 全量重算一轮：候选集逐帖算分 ZADD 快照、跌出候选集 ZREM、PFCOUNT 回写 lk_post.viewed。
     *
     * @return 本轮实际入榜条数（观测口径，测试断言用）
     */
    long runOnce();

    /**
     * 热榜 TopN：快照 ZSet 倒序 + 现存过审帖回填（limit 1~50）。
     */
    List<PostVO> top(int limit);
}
