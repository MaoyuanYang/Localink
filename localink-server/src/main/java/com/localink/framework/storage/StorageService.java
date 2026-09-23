package com.localink.framework.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 可插拔存储接口（M6-A，F-COM-07）：实现按 localink.storage.type 装配
 * （local 现有，OSS/MinIO 未来加实现换 type 即可）。发帖图片的前置能力。
 */
public interface StorageService {

    /**
     * 存储文件，返回可直接访问的 URL（相对路径，形如 /upload/202609/uuid.png）。
     */
    String store(MultipartFile file);

    /**
     * 删除 URL 对应的文件（删帖不清理图片——孤儿文件声明见任务卡，接口先备着）。
     */
    void delete(String url);
}
