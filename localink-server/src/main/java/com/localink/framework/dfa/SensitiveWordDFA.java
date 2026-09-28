package com.localink.framework.dfa;

import java.util.HashMap;
import java.util.Map;

/**
 * 敏感词 DFA（字典树=Trie 的 HashMap 嵌套实现，M6-F 同步初筛/异步复审共用引擎、各持词库）。
 *
 * 复杂度 O(文本长度 × 最大词长)，与词库大小无关——每个起点最多下探最大词长层；
 * "为什么不用正则"：正则内部本是自动机，但海量词编进一个正则的分支不可控、编译回溯风险高。
 *
 * 两个细节：
 * - end 标记（END_KEY）防前缀漏判/误判：只有走到 end 才算成词——词库有"赌博网站"，
 *   文本只含"赌博"不命中（前缀到达不算词）
 * - 跳干扰字符（非字母数字一律跳过）：空格/星号/标点不参与状态推进——"赌 博"照样命中，
 *   让绕过成本高于识别成本
 */
public final class SensitiveWordDFA {

    /**
     * 成词标记：子表中的特殊键（'\0' 不会出现在正常文本）。
     */
    private static final Character END_KEY = '\0';

    private final Map<Character, Object> root = new HashMap<>();

    private SensitiveWordDFA() {
    }

    public static SensitiveWordDFA of(Iterable<String> words) {
        SensitiveWordDFA dfa = new SensitiveWordDFA();
        for (String word : words) {
            if (word == null || word.isBlank()) {
                continue;
            }
            dfa.addWord(word);
        }
        return dfa;
    }

    @SuppressWarnings("unchecked")
    private void addWord(String word) {
        Map<Character, Object> current = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (isNoise(c)) {
                continue;
            }
            current = (Map<Character, Object>) current.computeIfAbsent(c, k -> new HashMap<Character, Object>());
        }
        current.put(END_KEY, Boolean.TRUE);
    }

    /**
     * 文本是否命中任一敏感词。
     */
    @SuppressWarnings("unchecked")
    public boolean contains(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            Map<Character, Object> current = root;
            int j = i;
            while (j < text.length()) {
                char c = text.charAt(j);
                j++;
                if (isNoise(c)) {
                    continue;
                }
                Object next = current.get(c);
                if (next == null) {
                    break;
                }
                current = (Map<Character, Object>) next;
                if (current.containsKey(END_KEY)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 干扰字符=非字母非数字（空格/标点/符号）：识别时跳过，不参与 Trie 状态推进。
     */
    private static boolean isNoise(char c) {
        return !Character.isLetterOrDigit(c);
    }
}
