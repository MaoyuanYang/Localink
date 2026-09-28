package com.localink.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * ES 客户端装配（M6-D）：RestClient（低层 HTTP，连接池）→ RestClientTransport（JSON 映射）
 * → ElasticsearchClient（类型安全 DSL）。定位是纯装配——mapping 与查询 DSL 是业务语义，
 * 放服务端组装（同 cache-starter 只封装结构操作、不掺业务）。
 */
@AutoConfiguration
@ConditionalOnClass(ElasticsearchClient.class)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RestClient.class)
    public RestClient restClient(SearchProperties properties) {
        return RestClient.builder(
                        properties.getUris().stream().map(HttpHost::create).toArray(HttpHost[]::new))
                .setRequestConfigCallback(requestConfig -> requestConfig
                        .setConnectTimeout(properties.getConnectTimeoutMs())
                        .setSocketTimeout(properties.getSocketTimeoutMs()))
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ElasticsearchTransport.class)
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
    }

    @Bean
    @ConditionalOnMissingBean(ElasticsearchClient.class)
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
