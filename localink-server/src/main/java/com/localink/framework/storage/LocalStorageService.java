package com.localink.framework.storage;

import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘存储实现（M6-A）：按月分目录 + UUID 文件名（防重防猜测），
 * 返回相对 URL 由 StorageWebConfig 的静态映射承接访问。
 */
@Component
@EnableConfigurationProperties(StorageProperties.class)
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp");

    private final StorageProperties properties;

    @Override
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "上传文件为空");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "仅支持图片格式: " + ALLOWED_EXTENSIONS);
        }
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String filename = UUID.randomUUID() + "." + extension;
        Path target = Paths.get(properties.getDir(), month, filename);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "文件存储失败: " + e.getMessage());
        }
        return properties.getUrlPrefix() + "/" + month + "/" + filename;
    }

    @Override
    public void delete(String url) {
        if (url == null || !url.startsWith(properties.getUrlPrefix() + "/")) {
            return;
        }
        Path target = Paths.get(properties.getDir(),
                url.substring(properties.getUrlPrefix().length() + 1));
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new LocalinkException(BaseCode.SYSTEM_ERROR, "文件删除失败: " + e.getMessage());
        }
    }

    private String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
