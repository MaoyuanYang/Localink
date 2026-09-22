package com.localink.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * API 契约只 JSON：ShardingSphere 的传递依赖带进 jackson-dataformat-xml，
 * MVC 会注册 XML converter 并在内容协商中抢占响应格式（Result 被序列化成 XML）。
 * extendMessageConverters 在自动配置完成后裁掉 XML converter——保留 SS 运行时所需的 jar，
 * 只收回 MVC 的响应格式决定权。
 */
@Configuration
public class JsonOnlyWebConfig implements WebMvcConfigurer {

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(converter -> !(converter instanceof MappingJackson2HttpMessageConverter)
                && converter.getClass().getName().contains("Xml"));
    }
}
