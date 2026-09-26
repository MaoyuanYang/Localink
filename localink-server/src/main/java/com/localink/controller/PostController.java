package com.localink.controller;

import com.localink.api.dto.CommentCreateDTO;
import com.localink.api.dto.PostCreateDTO;
import com.localink.api.vo.CommentVO;
import com.localink.api.vo.PageVO;
import com.localink.api.vo.PostVO;
import com.localink.common.result.Result;
import com.localink.service.CommentService;
import com.localink.service.LikeService;
import com.localink.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 帖子与评论（M6-A）：发帖（图片 URL 来自 /api/upload/image）/删帖/详情/分页/两级评论。
 * 点赞互动（M6-B）：赞/取消（幂等）、点赞榜 TopN、评论点赞（仅计数）。
 */
@RestController
@RequestMapping("/api/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final CommentService commentService;
    private final LikeService likeService;

    @PostMapping
    public Result<String> create(@Validated @RequestBody PostCreateDTO dto) {
        return Result.ok(postService.create(dto));
    }

    @DeleteMapping("/{postId}")
    public Result<Void> delete(@PathVariable Long postId) {
        postService.delete(postId);
        return Result.ok();
    }

    @GetMapping("/{postId}")
    public Result<PostVO> detail(@PathVariable Long postId) {
        return Result.ok(postService.detail(postId));
    }

    @GetMapping("/page")
    public Result<PageVO<PostVO>> page(@RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) Long shopId) {
        return Result.ok(postService.page(page, size, shopId));
    }

    @PostMapping("/comment")
    public Result<String> comment(@Validated @RequestBody CommentCreateDTO dto) {
        return Result.ok(commentService.create(dto));
    }

    @DeleteMapping("/comment/{commentId}")
    public Result<Void> deleteComment(@PathVariable Long commentId) {
        commentService.delete(commentId);
        return Result.ok();
    }

    @GetMapping("/{postId}/comment/page")
    public Result<PageVO<CommentVO>> commentPage(@PathVariable Long postId,
                                                 @RequestParam(defaultValue = "1") long page,
                                                 @RequestParam(defaultValue = "10") long size) {
        return Result.ok(commentService.pageOfPost(postId, page, size));
    }

    @PostMapping("/{postId}/like")
    public Result<Integer> like(@PathVariable Long postId) {
        return Result.ok(likeService.like(postId));
    }

    @DeleteMapping("/{postId}/like")
    public Result<Integer> unlike(@PathVariable Long postId) {
        return Result.ok(likeService.unlike(postId));
    }

    @GetMapping("/like/top")
    public Result<List<PostVO>> likeTop(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(likeService.top(limit));
    }

    @PostMapping("/comment/{commentId}/like")
    public Result<Integer> likeComment(@PathVariable Long commentId) {
        return Result.ok(likeService.likeComment(commentId));
    }
}
