package com.localink.search.test;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ES 客户端装配冒烟（M6-D）：自动配置产出可用 ElasticsearchClient，能连通本机 ES
 * （compose --profile es 启动，免认证 9200）并读到集群健康状态。前置：ES 容器在跑。
 */
@SpringBootTest
class SearchClientSmokeTest {

    @Autowired
    private ElasticsearchClient client;

    @Test
    void autoConfiguredClientReachesLocalElasticsearch() throws Exception {
        assertNotNull(client);
        HealthResponse health = client.cluster().health(h -> h);
        assertNotNull(health.clusterName());
        assertNotNull(health.status(), "应能读到集群状态（无索引的单节点通常为 green）");
    }
}
