package com.localink.service;

import com.localink.api.vo.PostVO;
import com.localink.api.vo.ScrollVO;

/**
 * 关注流（M6-C）：推模式（发帖事件推送粉丝收件箱，大 V 不推）+ 读端归并
 * （收件箱页 ∪ 我关注的大 V 帖页，按 score 归并）+ score 游标滚动分页。
 */
public interface FeedService {

    /**
     * 关注流（登录必须）：两路候选按 score 归并去重取前 size 条，按当前关注集合过滤
     * （取关即时生效）；nextCursor=null 表示没有更多。
     */
    ScrollVO<PostVO> feed(Long lastScore, int size);
}
