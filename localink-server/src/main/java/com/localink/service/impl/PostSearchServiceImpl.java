package com.localink.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.api.vo.PostSearchVO;
import com.localink.api.vo.SearchVO;
import com.localink.api.vo.ShopFacetVO;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.entity.Post;
import com.localink.entity.Shop;
import com.localink.mapper.PostMapper;
import com.localink.mapper.ShopMapper;
import com.localink.search.PostDocument;
import com.localink.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 帖子搜索实现（M6-D）：ES 是 lk_post 的派生视图——消息驱动增量同步，rebuildAll 全量重算。
 * 检索原语（倒排/算分/聚合）由 ES 提供，本类的工作是 mapping 设计与 DSL 组装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchServiceImpl implements PostSearchService {

    private static final String INDEX = "post";
    private static final int MAX_SIZE = 50;

    /**
     * ik 不对称分词：索引进粗（ik_max_word，切出所有组合保召回），查询用细（ik_smart，
     * 切最少组合保精确）。title/content 为 text；shopId 聚合与 post_filter 用 long
     * 直接可 terms；images 仅存储不索引。
     */
    private static final String MAPPING_JSON = """
            {
              "properties": {
                "postId": {"type": "long"},
                "title": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "content": {"type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart"},
                "nickName": {"type": "keyword"},
                "shopId": {"type": "long"},
                "userId": {"type": "long"},
                "liked": {"type": "integer"},
                "createTime": {"type": "date", "format": "yyyy-MM-dd HH:mm:ss||epoch_millis"},
                "images": {"type": "keyword", "index": false}
              }
            }
            """;

    private final ElasticsearchClient client;
    private final PostMapper postMapper;
    private final ShopMapper shopMapper;
    private final PostServiceImpl postService;

    @Override
    public boolean indexPost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != 1) {
            return false;
        }
        String nickName = postService.nickNamesOf(List.of(post.getUserId()))
                .getOrDefault(post.getUserId(), "匿名用户");
        List<String> images = post.getImages() == null || post.getImages().isBlank()
                ? List.of() : Arrays.asList(post.getImages().split(","));
        PostDocument doc = PostDocument.of(post, nickName,
                escapeHtml(post.getTitle()), escapeHtml(post.getContent()), images);
        try {
            es(() -> client.index(i -> i.index(INDEX).id(String.valueOf(postId)).document(doc)));
            return true;
        } catch (ElasticsearchException e) {
            // 索引尚不存在等可自愈场景：建索引后重试一次；仍失败抛出走消息重投
            ensureIndex();
            es(() -> client.index(i -> i.index(INDEX).id(String.valueOf(postId)).document(doc)));
            return true;
        }
    }

    @Override
    public void deletePostFromIndex(Long postId) {
        try {
            es(() -> client.delete(d -> d.index(INDEX).id(String.valueOf(postId))));
        } catch (ElasticsearchException e) {
            if (e.status() != 404) {
                throw e;
            }
        }
    }

    @Override
    public SearchVO search(String keyword, Long shopId, String sort, String searchAfter, int size) {
        if (keyword == null || keyword.isBlank()) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "关键词不能为空");
        }
        boolean byTime;
        if (sort == null || sort.isBlank() || "relevance".equals(sort)) {
            byTime = false;
        } else if ("time".equals(sort)) {
            byTime = true;
        } else {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "sort 仅支持 relevance/time");
        }
        int bounded = Math.max(1, Math.min(MAX_SIZE, size));
        List<FieldValue> after = parseAfter(searchAfter, byTime);

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(INDEX)
                .size(bounded)
                // keyword 参与算分（title 加权 2 倍：标题命中比正文命中更相关）
                .query(q -> q.multiMatch(mm -> mm.query(keyword).fields("title^2", "content")))
                .highlight(h -> h
                        .fields("title", f -> f.preTags("<em>").postTags("</em>").numberOfFragments(0))
                        .fields("content", f -> f.preTags("<em>").postTags("</em>")
                                .fragmentSize(100).numberOfFragments(1)))
                .aggregations("shops", a -> a.terms(t -> t.field("shopId").size(20)));
        // shopId 走 post_filter：不算分（过滤不影响相关度）且不作用于聚合（侧栏保全集）
        if (shopId != null) {
            builder.postFilter(f -> f.term(t -> t.field("shopId").value(shopId)));
        }
        if (byTime) {
            builder.sort(s -> s.field(f -> f.field("createTime").order(SortOrder.Desc)));
        } else {
            builder.sort(s -> s.score(sc -> sc.order(SortOrder.Desc)));
        }
        // 排序键拼 tie-breaker：score/createTime 都可重，不拼 postId 深翻页会重会漏
        builder.sort(s -> s.field(f -> f.field("postId").order(SortOrder.Desc)));
        if (after != null) {
            builder.searchAfter(after);
        }

        SearchResponse<PostDocument> response;
        try {
            response = es(() -> client.search(builder.build(), PostDocument.class));
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 索引尚未创建：空结果而非报错（rebuild 之前搜索不炸）
                return SearchVO.of(List.of(), null, List.of());
            }
            throw e;
        }

        List<Hit<PostDocument>> hits = response.hits().hits();
        List<PostSearchVO> records = hits.stream().map(this::toVo).collect(Collectors.toList());
        String next = hits.size() == bounded && !hits.isEmpty()
                ? joinSortValues(hits.get(hits.size() - 1).sort()) : null;
        return SearchVO.of(records, next, shopFacets(response));
    }

    @Override
    public long rebuildAll() {
        try {
            if (es(() -> client.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value())) {
                es(() -> client.indices().delete(d -> d.index(INDEX)));
            }
        } catch (ElasticsearchException e) {
            log.warn("重建前删索引失败（继续走建索引）: {}", e.getMessage());
        }
        ensureIndex();
        List<Post> posts = postMapper.selectList(new LambdaQueryWrapper<Post>()
                .eq(Post::getAuditStatus, 1));
        long count = 0;
        for (Post post : posts) {
            if (indexPost(post.getId())) {
                count++;
            }
        }
        refresh();
        log.info("ES 全量重建完成, indexed={}", count);
        return count;
    }

    @Override
    public void refresh() {
        try {
            es(() -> client.indices().refresh(r -> r.index(INDEX)));
        } catch (ElasticsearchException e) {
            log.warn("索引刷新失败（索引可能不存在）: {}", e.getMessage());
        }
    }

    private void ensureIndex() {
        try {
            if (es(() -> client.indices().exists(ExistsRequest.of(e -> e.index(INDEX))).value())) {
                return;
            }
            es(() -> client.indices().create(c -> c.index(INDEX).mappings(
                    m -> m.withJson(new StringReader(MAPPING_JSON)))));
            // 单节点建索引后主分片有短暂启动窗口（此刻写入/查询 503 no_shard_available），
            // 阻塞等 yellow（副本分片在单节点永不分配，yellow 即主分片就绪）
            es(() -> client.cluster().health(h -> h.index(INDEX)
                    .waitForStatus(HealthStatus.Yellow).timeout(t -> t.time("10s"))));
        } catch (Exception e) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "ES 索引初始化失败: " + e.getMessage());
        }
    }

    private PostSearchVO toVo(Hit<PostDocument> hit) {
        PostDocument doc = hit.source();
        PostSearchVO vo = new PostSearchVO();
        vo.setId(doc.getPostId());
        vo.setTitle(doc.getTitle());
        List<String> hl = hit.highlight().get("title");
        vo.setTitleHighlight(hl == null || hl.isEmpty() ? null : hl.get(0));
        hl = hit.highlight().get("content");
        vo.setContentHighlight(hl == null || hl.isEmpty() ? null : hl.get(0));
        vo.setNickName(doc.getNickName());
        vo.setShopId(doc.getShopId());
        vo.setLiked(doc.getLiked());
        vo.setCreateTime(doc.getCreateTime());
        return vo;
    }

    private List<ShopFacetVO> shopFacets(SearchResponse<PostDocument> response) {
        if (response.aggregations() == null || response.aggregations().get("shops") == null
                || !response.aggregations().get("shops").isLterms()) {
            return List.of();
        }
        List<Long> shopIds = new ArrayList<>();
        List<ShopFacetVO> facets = new ArrayList<>();
        for (LongTermsBucket bucket : response.aggregations().get("shops").lterms().buckets().array()) {
            shopIds.add(bucket.key());
            facets.add(ShopFacetVO.of(bucket.key(), null, bucket.docCount()));
        }
        if (shopIds.isEmpty()) {
            return facets;
        }
        Map<Long, String> names = shopMapper.selectBatchIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, Shop::getName, (a, b) -> a));
        for (ShopFacetVO facet : facets) {
            facet.setShopName(names.getOrDefault(facet.getShopId(), "未知商户"));
        }
        return facets;
    }

    private List<FieldValue> parseAfter(String searchAfter, boolean byTime) {
        if (searchAfter == null || searchAfter.isBlank()) {
            return null;
        }
        String[] parts = searchAfter.split("\\|");
        if (parts.length != 2) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "searchAfter 格式非法");
        }
        try {
            FieldValue first = byTime
                    ? FieldValue.of(Long.parseLong(parts[0]))
                    : FieldValue.of(Double.parseDouble(parts[0]));
            return List.of(first, FieldValue.of(Long.parseLong(parts[1])));
        } catch (NumberFormatException e) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "searchAfter 格式非法");
        }
    }

    private String joinSortValues(List<FieldValue> sortValues) {
        return sortValues.stream().map(v -> {
            if (v.isDouble()) {
                return String.valueOf(v.doubleValue());
            }
            if (v.isLong()) {
                return String.valueOf(v.longValue());
            }
            return v.stringValue();
        }).collect(Collectors.joining("|"));
    }

    /**
     * 入索引前 HTML 转义：ES 高亮加的 &lt;em&gt; 成为片段中唯一标记，前端任意渲染无注入面。
     */
    private static String escapeHtml(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * ES client 全量受检 IOException 的统一包装：IO 层故障=系统错误（SYSTEM_ERROR），
     * ElasticsearchException 是运行时异常（404 幂等/索引缺失等按场景处理）原样穿透。
     */
    private <T> T es(EsCall<T> call) {
        try {
            return call.get();
        } catch (IOException e) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "ES 访问失败: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface EsCall<T> {
        T get() throws IOException;
    }
}
