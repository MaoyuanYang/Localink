package com.localink.controller;

import com.localink.api.vo.UserBriefVO;
import com.localink.common.result.Result;
import com.localink.service.FollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 关注关系（M6-B）：关注/取关（幂等）、共同关注（Set 交集）。
 * 粉丝明细走 lk_follow 反查不建 Redis Set（大 V 巨型集合），M6-C Feed 使用。
 */
@RestController
@RequestMapping("/api/follow")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @PostMapping("/{userId}")
    public Result<Void> follow(@PathVariable Long userId) {
        followService.follow(userId);
        return Result.ok();
    }

    @DeleteMapping("/{userId}")
    public Result<Void> unfollow(@PathVariable Long userId) {
        followService.unfollow(userId);
        return Result.ok();
    }

    @GetMapping("/common/{userId}")
    public Result<List<UserBriefVO>> commonFollows(@PathVariable Long userId) {
        return Result.ok(followService.commonFollows(userId));
    }
}
