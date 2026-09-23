package com.localink.api.vo;

import java.util.List;

/**
 * 分页响应（对齐 web-frontend.md 第 5 节约定：page/size 请求，total/records 响应）。
 */
public record PageVO<T>(long total, List<T> records) {

    public static <T> PageVO<T> of(long total, List<T> records) {
        return new PageVO<>(total, records);
    }
}
