package com.localink.service;

import com.localink.api.dto.PostCreateDTO;
import com.localink.api.vo.PageVO;
import com.localink.api.vo.PostVO;

/**
 * 帖子（M6-A）：发帖/删帖/详情/分页。
 */
public interface PostService {

    String create(PostCreateDTO dto);

    /**
     * 删除（仅作者本人）：级联物理删评论。
     */
    void delete(Long postId);

    /**
     * 详情：仅返回审核通过（audit=1）的帖子；每次浏览 viewed+1（M6-E 换 HLL UV）。
     */
    PostVO detail(Long postId);

    /**
     * 分页：audit=1、createTime 倒序；shopId 可选过滤。
     */
    PageVO<PostVO> page(long page, long size, Long shopId);
}
