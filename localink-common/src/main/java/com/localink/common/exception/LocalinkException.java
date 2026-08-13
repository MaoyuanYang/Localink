package com.localink.common.exception;

import com.localink.common.code.BaseCode;
import lombok.Getter;

/**
 * 业务异常。业务错误统一抛出本异常，由全局异常处理器转换为 Result。
 */
@Getter
public class LocalinkException extends RuntimeException {

    private final int code;

    public LocalinkException(BaseCode baseCode) {
        this(baseCode.getCode(), baseCode.getMessage());
    }

    public LocalinkException(BaseCode baseCode, String message) {
        this(baseCode.getCode(), message);
    }

    public LocalinkException(int code, String message) {
        super(message);
        this.code = code;
    }
}
