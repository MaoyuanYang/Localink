package com.localink.framework.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储配置（M6-A）：type 选择实现；dir 为本地存储根目录；urlPrefix 为对外访问前缀。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.storage")
public class StorageProperties {

    /**
     * 存储实现类型（local=本地磁盘；未来 oss/minio）。
     */
    private String type = "local";

    /**
     * 本地存储根目录（相对工作目录或绝对路径）。
     */
    private String dir = "./upload";

    /**
     * 对外 URL 前缀（与静态资源映射一致）。
     */
    private String urlPrefix = "/upload";
}
