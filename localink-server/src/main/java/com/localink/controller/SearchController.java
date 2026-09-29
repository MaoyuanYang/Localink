package com.localink.controller;

import com.localink.api.vo.SearchVO;
import com.localink.common.result.Result;
import com.localink.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 帖子搜索（M6-D）：公开浏览（不强制登录）。searchAfter 游标分页契约见 web-frontend.md §5。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final PostSearchService postSearchService;

    @GetMapping("/post")
    public Result<SearchVO> searchPost(@RequestParam String keyword,
                                       @RequestParam(required = false) Long shopId,
                                       @RequestParam(defaultValue = "relevance") String sort,
                                       @RequestParam(required = false) String searchAfter,
                                       @RequestParam(defaultValue = "10") int size) {
        // 参数钳制：关键词长度与页大小封顶（ES 深分页由 search_after 承担，size 只服务首屏）
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty() || kw.length() > 64) {
            throw new com.localink.common.exception.LocalinkException(
                    com.localink.common.code.BaseCode.PARAM_ERROR, "关键词长度须在 1~64 字符内");
        }
        int safeSize = Math.min(Math.max(1, size), 50);
        return Result.ok(postSearchService.search(kw, shopId, sort, searchAfter, safeSize));
    }
}
