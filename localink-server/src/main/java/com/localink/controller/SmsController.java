package com.localink.controller;

import com.localink.api.dto.SmsCodeSendDTO;
import com.localink.common.result.Result;
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

    @PostMapping("/code")
    public Result<Void> sendCode(@Validated @RequestBody SmsCodeSendDTO dto) {
        smsService.sendCode(dto.getPhone());
        return Result.ok();
    }
}
