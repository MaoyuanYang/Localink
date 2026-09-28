package com.localink.dfa;

import com.localink.framework.dfa.SensitiveWordDFA;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DFA 引擎纯逻辑验证（不起 Spring）：命中/前缀不命中（end 标记）/跳干扰字符/多词库/边界。
 */
class SensitiveWordDfaTest {

    private final SensitiveWordDFA dfa = SensitiveWordDFA.of(List.of("赌博网站", "代开发票"));

    @Test
    void hitsFullWord() {
        assertTrue(dfa.contains("欢迎来赌博网站玩两把"));
        assertTrue(dfa.contains("可代开发票联系我"));
    }

    @Test
    void prefixAloneDoesNotHit() {
        // 词库只有"赌博网站"：文本仅含前缀"赌博"不算成词——end 标记防前缀误判
        assertFalse(dfa.contains("我在赌博"));
        assertTrue(dfa.contains("赌博网站们是好人家的店"), "文本含完整词即命中（子串匹配语义）");
    }

    @Test
    void noiseCharactersAreSkipped() {
        assertTrue(dfa.contains("赌 博 网 站"), "空格干扰照样命中");
        assertTrue(dfa.contains("赌*博*网*站"), "符号干扰照样命中");
        assertTrue(dfa.contains("代、开、发、票"));
    }

    @Test
    void cleanTextAndEmptyInputsDoNotHit() {
        assertFalse(dfa.contains("今天天气不错，去西湖走走"));
        assertFalse(dfa.contains(""));
        assertFalse(dfa.contains(null));
        assertFalse(SensitiveWordDFA.of(List.of()).contains("赌博网站"));
    }
}
