package com.localink.framework.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 上传文件静态访问映射：/upload/** → 本地存储目录（M6-A）。
 */
@Configuration
@RequiredArgsConstructor
public class StorageWebConfig implements WebMvcConfigurer {

    private final StorageProperties properties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolute = Paths.get(properties.getDir()).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler(properties.getUrlPrefix() + "/**")
                .addResourceLocations(absolute);
    }
}
