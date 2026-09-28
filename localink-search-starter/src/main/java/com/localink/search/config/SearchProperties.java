package com.localink.search.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 搜索配置（M6-D）：ES 节点地址与超时。单节点免认证（compose 关闭 xpack.security）；
 * 集群/认证场景演进：uris 多节点轮询 + username/password 走 RestClientBuilder 的
 * BasicAuthHeaderSupplier。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.search")
public class SearchProperties {

    private List<String> uris = List.of("http://localhost:9200");

    private int connectTimeoutMs = 2_000;

    private int socketTimeoutMs = 10_000;
}
