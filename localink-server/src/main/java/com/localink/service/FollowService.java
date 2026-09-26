package com.localink.service;

import com.localink.api.vo.UserBriefVO;

import java.util.List;

/**
 * 关注关系（M6-B）：关注/取关（幂等）、共同关注（Set 交集）。
 * 粉丝明细不建 Redis Set，走 lk_follow 反查（大 V 巨型集合，M6-C Feed 拉模式伏笔）。
 */
public interface FollowService {

    /**
     * 关注：关系行 + fans/followee 双侧冗余计数同事务，提交后关注 Set SADD；重复关注幂等。
     */
    void follow(Long followUserId);

    /**
     * 取关：删关系行 + 条件递减双侧计数，提交后关注 Set SREM；未关注幂等。
     */
    void unfollow(Long followUserId);

    /**
     * 共同关注：我与目标用户关注 Set 的交集，昵称/头像回填。
     */
    List<UserBriefVO> commonFollows(Long targetUserId);
}
