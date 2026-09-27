package com.localink.service;

import com.localink.api.dto.PostCreateDTO;
import com.localink.api.vo.PageVO;
import com.localink.api.vo.PostVO;

import java.util.List;

/**
 * 帖子（M6-A）：发帖/删帖/详情/分页；M6-B：删帖级联清点赞与榜单、按序批量取帖。
 */
public interface PostService {

    String create(PostCreateDTO dto);

    /**
     * 删除（仅作者本人）：级联物理删评论与点赞行，并从点赞榜 ZREM（M6-B 补齐级联矩阵）。
     */
    void delete(Long postId);

    /**
     * 详情：仅返回审核通过（audit=1）的帖子；每次浏览 viewed+1（M6-E 换 HLL UV）；
     * 登录态回填 meLiked（M6-B）。
     */
    PostVO detail(Long postId);

    /**
     * 分页：audit=1、createTime 倒序；shopId 可选过滤。
     */
    PageVO<PostVO> page(long page, long size, Long shopId);

    /**
     * 按传入顺序返回过审帖 VO：缺失/未过审跳过（M6-B 点赞榜等按序回填场景）。
     */
    List<PostVO> listOrdered(List<Long> orderedIds);
}
