package com.localink.config;

import com.localink.framework.dfa.SensitiveWordDFA;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DFA 词库装配（M6-F，仿 lua 脚本的 ClassPathResource 惯例）：
 * 显性词库（同步初筛，命中拒发帖）与隐性词库（异步复审，命中先发后收回）分离——
 * 信任分级的物理化。演进：DB 词库 + 管理端热更新（随 W2 审核队列页一起做）。
 */
@Configuration
public class DfaConfig {

    @Bean
    public SensitiveWordDFA sensitiveWordDfa() throws IOException {
        return SensitiveWordDFA.of(loadWords("dict/sensitive-words.txt"));
    }

    @Bean
    public SensitiveWordDFA auditRiskWordDfa() throws IOException {
        return SensitiveWordDFA.of(loadWords("dict/audit-risk-words.txt"));
    }

    private List<String> loadWords(String path) throws IOException {
        List<String> words = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    words.add(line);
                }
            }
        }
        return words;
    }
}
