package com.localink.common.code;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 统一错误码枚举。分段约定：0 成功；1xxxx 券域（普通/秒杀）；2xxxx 用户；3xxxx 社区；4xxxx 框架/通用；5xxxx 商户。
 */
@Getter
@RequiredArgsConstructor
public enum BaseCode {

    SUCCESS(0, "成功"),

    VOUCHER_NOT_AVAILABLE(10001, "该券暂不可领取"),
    SECKILL_NOT_STARTED(10002, "秒杀尚未开始"),
    SECKILL_ENDED(10003, "秒杀已结束"),
    SECKILL_STOCK_NOT_ENOUGH(10004, "库存不足"),
    SECKILL_DUPLICATE_ORDER(10005, "每人限购一单"),
    SECKILL_LEVEL_NOT_ENOUGH(10006, "会员等级不满足抢购条件"),

    SMS_SEND_TOO_FREQUENT(20001, "请勿频繁获取验证码"),
    SMS_CODE_INVALID(20002, "验证码错误"),
    SMS_CODE_EXPIRED(20003, "验证码已过期，请重新获取"),

    SYSTEM_ERROR(40000, "系统繁忙，请稍后再试"),
    PARAM_ERROR(40001, "请求参数错误"),
    UNAUTHORIZED(40002, "未登录或登录已过期"),
    FORBIDDEN(40003, "无访问权限"),
    NOT_FOUND(40004, "资源不存在"),
    LOCK_TIMEOUT(40005, "操作繁忙，请稍后重试"),
    MQ_SEND_FAILED(40006, "消息发送失败"),

    SHOP_TYPE_IN_USE(50001, "该类型下仍有商户，无法删除");

    private final int code;
    private final String message;
}
