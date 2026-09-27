package com.localink.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feed 配置（M6-C）：bigVFansThreshold 是推/挽分界——作者粉丝数达阈值即"大 V"，
 * 发帖不再推收件箱（写扩散上界被该值封顶），粉丝读时从 DB 拉。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "localink.feed")
public class FeedProperties {

    private int bigVFansThreshold = 1000;

    private int inboxMaxSize = 1024;
}
