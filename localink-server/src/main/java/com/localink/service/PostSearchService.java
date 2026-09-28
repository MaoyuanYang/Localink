package com.localink.service;

import com.localink.api.vo.SearchVO;

/**
 * 帖子搜索（M6-D）：ES 是 DB 的派生视图（事实源 lk_post，消息驱动增量同步+全量重建兜底）。
 */
public interface PostSearchService {

    /**
     * 增量索引一帖（消费 UPSERT 消息调用）：查事实源组装文档 upsert（幂等）。
     *
     * @return false=帖子不存在或未过审（跳过，防御发帖后被删/驳回的窗口）
     */
    boolean indexPost(Long postId);

    /**
     * 删 ES 文档（消费 DELETE 消息调用）；404 视为已删。
     */
    void deletePostFromIndex(Long postId);

    /**
     * 搜索（一次 _search 三段：query 算分 / highlight 高亮 / aggs 商户分面）。
     * shopId 走 post_filter——不参与算分且不影响聚合（侧栏保全集）；searchAfter 为
     * 上一页 nextSearchAfter 原样回传，null=第一页；sort: relevance（默认）| time。
     */
    SearchVO search(String keyword, Long shopId, String sort, String searchAfter, int size);

    /**
     * 全量重建：删索引→建 mapping→扫 DB audit=1 全量灌入。fire-and-forget 丢失与索引
     * 损坏的兜底（管理端入口 W2/W3 演进）。
     *
     * @return 实际灌入文档数
     */
    long rebuildAll();

    /**
     * 强制刷新索引分片（测试用：ES 近实时检索默认 1s 可见性窗口，测试需立即可查）。
     */
    void refresh();
}
