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
        return Result.ok(postSearchService.search(keyword, shopId, sort, searchAfter, size));
    }
}
