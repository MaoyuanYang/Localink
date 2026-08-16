package com.localink.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 统一错误码枚举。分段约定：0 成功；1xxxx 秒杀券；2xxxx 用户；3xxxx 社区；4xxxx 框架/通用。
 */
@Getter
@RequiredArgsConstructor
public enum BaseCode {

    SUCCESS(0, "成功"),

    SMS_SEND_TOO_FREQUENT(20001, "请勿频繁获取验证码"),

    SYSTEM_ERROR(40000, "系统繁忙，请稍后再试"),
    PARAM_ERROR(40001, "请求参数错误"),
    UNAUTHORIZED(40002, "未登录或登录已过期"),
    FORBIDDEN(40003, "无访问权限"),
    NOT_FOUND(40004, "资源不存在");

    private final int code;
    private final String message;
}
