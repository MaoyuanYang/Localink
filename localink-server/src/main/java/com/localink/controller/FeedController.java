package com.localink.controller;

import com.localink.api.vo.PostVO;
import com.localink.api.vo.ScrollVO;
import com.localink.common.result.Result;
import com.localink.service.FeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注流（M6-C）：score 游标滚动分页（ScrollVO 契约，与 page/size 分页并存）；
 * 推挽结合对读端透明——收件箱与大 V 帖在读端归并。
 */
@RestController
@RequestMapping("/api/feed")
@RequiredArgsConstructor
public class FeedController {

    private final FeedService feedService;

    @GetMapping
    public Result<ScrollVO<PostVO>> feed(@RequestParam(required = false) Long lastScore,
                                         @RequestParam(defaultValue = "10") int size) {
        return Result.ok(feedService.feed(lastScore, size));
    }
}
