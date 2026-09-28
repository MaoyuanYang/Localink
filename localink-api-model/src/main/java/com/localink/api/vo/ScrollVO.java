package com.localink.api.vo;

import java.util.List;

/**
 * 滚动分页视图（M6-C，Feed 关注流首次落地）：records 按 score 倒序；nextCursor 为下一页
 * 游标（把最后一条的 score 原样回传），null 表示没有更多。与 PageVO 的 page/size 契约
 * 并存——游标分页服务时间线场景（插入敏感，offset 会重复/漏）。
 */
public record ScrollVO<T>(List<T> records, Long nextCursor) {

    public static <T> ScrollVO<T> of(List<T> records, Long nextCursor) {
        return new ScrollVO<>(records, nextCursor);
    }
}
