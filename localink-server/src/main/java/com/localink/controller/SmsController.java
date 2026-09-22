package com.localink.controller;

import com.localink.api.dto.SmsCodeSendDTO;
import com.localink.common.result.Result;
import com.localink.ratelimit.Dimension;
import com.localink.ratelimit.RateLimit;
import com.localink.service.SmsService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsService smsService;

    /**
     * 验证码发送限流（M3.14 首个场景接入）：短信通道按 IP 维度防刷（未登录接口，用户维度不适用）。
     */
    @RateLimit(scene = "sms-send", dimensions = Dimension.IP)
    @PostMapping("/code")
    public Result<Void> sendCode(@Validated @RequestBody SmsCodeSendDTO dto) {
        smsService.sendCode(dto.getPhone());
        return Result.ok();
    }
}
