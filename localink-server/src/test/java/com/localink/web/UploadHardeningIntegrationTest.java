package com.localink.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.constant.KeyManage;
import com.localink.entity.User;
import com.localink.framework.holder.UserHolder;
import com.localink.framework.storage.StorageProperties;
import com.localink.mapper.UserMapper;
import com.localink.service.SmsService;
import com.localink.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T1 上传加固矩阵（2026-10-02）：验证图片上传的鉴权、扩展名白名单、
 * 空文件拒绝与路径穿越中性化（UUID 重命名 + 按月目录）。
 * 5MB 上限由容器层 multipart 配置把关，MockMvc 不经过容器解析，不在套件内断言（见 VERIFICATION）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadHardeningIntegrationTest {

    private static final String PHONE = "13900139102";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SmsService smsService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private KeyBuilder keyBuilder;

    @Autowired
    private StorageProperties storageProperties;

    private final List<String> issuedTokens = new ArrayList<>();
    private final List<Path> writtenFiles = new ArrayList<>();

    @AfterEach
    void cleanup() {
        redisCache.delete(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        issuedTokens.forEach(token -> redisCache.delete(keyBuilder.build(KeyManage.USER_TOKEN, token)));
        userMapper.delete(new LambdaQueryWrapper<User>().eq(User::getPhone, PHONE));
        writtenFiles.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        });
        UserHolder.clear();
    }

    private String loginAndGetToken() {
        smsService.sendCode(PHONE);
        String code = redisCache.strings().getString(keyBuilder.build(KeyManage.SMS_CODE, PHONE));
        String token = userService.login(PHONE, code);
        issuedTokens.add(token);
        return token;
    }

    @Test
    void anonymousUploadRejected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/api/upload/image").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void nonImageExtensionRejected() throws Exception {
        String token = loginAndGetToken();
        MockMultipartFile file = new MockMultipartFile("file", "evil.php", "application/octet-stream",
                "<?php echo 1;".getBytes());
        mockMvc.perform(multipart("/api/upload/image").file(file).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
        MockMultipartFile file2 = new MockMultipartFile("file", "note.txt", "text/plain", "hi".getBytes());
        mockMvc.perform(multipart("/api/upload/image").file(file2).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void extensionlessFilenameRejected() throws Exception {
        String token = loginAndGetToken();
        MockMultipartFile file = new MockMultipartFile("file", "noext", "image/jpeg", new byte[]{1});
        mockMvc.perform(multipart("/api/upload/image").file(file).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void emptyFileRejected() throws Exception {
        String token = loginAndGetToken();
        MockMultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);
        mockMvc.perform(multipart("/api/upload/image").file(file).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.PARAM_ERROR.getCode()));
    }

    @Test
    void traversalFilenameNeutralizedByUuidRename() throws Exception {
        String token = loginAndGetToken();
        MockMultipartFile file = new MockMultipartFile("file", "../../evil.jpg", "image/jpeg",
                new byte[]{9, 9, 9});
        String url = mockMvc.perform(multipart("/api/upload/image").file(file).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BaseCode.SUCCESS.getCode()))
                .andReturn().getResponse().getContentAsString();
        String path = url.split("\"data\":\"")[1].split("\"")[0];
        assertFalse(path.contains(".."), "返回 URL 不应包含路径穿越片段: " + path);
        assertTrue(path.startsWith(storageProperties.getUrlPrefix() + "/"), "URL 应以静态映射前缀开头: " + path);
        Path stored = Paths.get(storageProperties.getDir(),
                path.substring(storageProperties.getUrlPrefix().length() + 1)).toAbsolutePath().normalize();
        assertTrue(stored.startsWith(
                        Paths.get(storageProperties.getDir()).toAbsolutePath().normalize()),
                "落盘路径必须仍在存储根目录内: " + stored);
        assertTrue(Files.exists(stored), "文件应已落盘: " + stored);
        writtenFiles.add(stored);
    }
}
