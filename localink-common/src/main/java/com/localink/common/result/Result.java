package com.localink.common.result;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.localink.common.code.BaseCode;
import lombok.Data;

/**
 * 统一返回体。业务码与 HTTP 码分工：业务结果一律通过 code 表达。
 */
@Data
public class Result<T> {

    private Integer code;
    private String message;
    private T data;

    public static <T> Result<T> ok() {
        return build(BaseCode.SUCCESS.getCode(), BaseCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return build(BaseCode.SUCCESS.getCode(), BaseCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(BaseCode baseCode) {
        return build(baseCode.getCode(), baseCode.getMessage(), null);
    }

    public static <T> Result<T> fail(BaseCode baseCode, String message) {
        return build(baseCode.getCode(), message, null);
    }

    public static <T> Result<T> fail(int code, String message) {
        return build(code, message, null);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return Integer.valueOf(BaseCode.SUCCESS.getCode()).equals(code);
    }

    private static <T> Result<T> build(int code, String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        return result;
    }
}
