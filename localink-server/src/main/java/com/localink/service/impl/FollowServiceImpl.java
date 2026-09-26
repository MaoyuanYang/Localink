package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.api.dto.UserDTO;
import com.localink.api.vo.UserBriefVO;
import com.localink.cache.KeyBuild;
import com.localink.cache.KeyBuilder;
import com.localink.cache.RedisCache;
import com.localink.common.code.BaseCode;
import com.localink.common.exception.LocalinkException;
import com.localink.constant.KeyManage;
import com.localink.entity.Follow;
import com.localink.entity.User;
import com.localink.framework.holder.UserHolder;
import com.localink.mapper.FollowMapper;
import com.localink.mapper.UserMapper;
import com.localink.service.FollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 关注关系实现（M6-B）：关系行 + fans/followee 双侧冗余计数同事务，关注 Set SADD/SREM
 * 走 afterCommit。只建"我关注谁"的 Set（上界=主动关注数），粉丝侧走 lk_follow 反查
 * + fans 计数——大 V 巨型粉丝集合不进 Redis。
 */
@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final FollowMapper followMapper;
    private final UserMapper userMapper;
    private final RedisCache redisCache;
    private final KeyBuilder keyBuilder;

    @Override
    @Transactional
    public void follow(Long followUserId) {
        Long userId = UserHolder.get().getId();
        if (followUserId.equals(userId)) {
            throw new LocalinkException(BaseCode.PARAM_ERROR, "不能关注自己");
        }
        if (userMapper.selectById(followUserId) == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "用户不存在");
        }
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(followUserId);
        try {
            followMapper.insert(follow);
        } catch (DuplicateKeyException e) {
            return;
        }
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .setSql("followee = followee + 1"));
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, followUserId)
                .setSql("fans = fans + 1"));
        KeyBuild mySet = keyBuilder.build(KeyManage.USER_FOLLOWEE, userId);
        TxCallbacks.afterCommit(() ->
                redisCache.sets().add(mySet, String.valueOf(followUserId)));
    }

    @Override
    @Transactional
    public void unfollow(Long followUserId) {
        Long userId = UserHolder.get().getId();
        int removed = followMapper.delete(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));
        if (removed == 0) {
            return;
        }
        // 条件递减防负数；fans/followee 是展示口径的非实时精确计数，漂移接受
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .gt(User::getFollowee, 0)
                .setSql("followee = followee - 1"));
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, followUserId)
                .gt(User::getFans, 0)
                .setSql("fans = fans - 1"));
        KeyBuild mySet = keyBuilder.build(KeyManage.USER_FOLLOWEE, userId);
        TxCallbacks.afterCommit(() ->
                redisCache.sets().remove(mySet, String.valueOf(followUserId)));
    }

    @Override
    public List<UserBriefVO> commonFollows(Long targetUserId) {
        UserDTO me = UserHolder.get();
        if (me == null) {
            throw new LocalinkException(BaseCode.UNAUTHORIZED, "请先登录");
        }
        if (userMapper.selectById(targetUserId) == null) {
            throw new LocalinkException(BaseCode.NOT_FOUND, "用户不存在");
        }
        Set<String> common = redisCache.sets().intersect(
                keyBuilder.build(KeyManage.USER_FOLLOWEE, me.getId()),
                keyBuilder.build(KeyManage.USER_FOLLOWEE, targetUserId), String.class);
        if (common.isEmpty()) {
            return List.of();
        }
        return toBriefVos(common.stream().map(Long::valueOf).toList());
    }

    private List<UserBriefVO> toBriefVos(List<Long> userIds) {
        return userMapper.selectBatchIds(userIds).stream().map(user -> {
            UserBriefVO vo = new UserBriefVO();
            vo.setUserId(user.getId());
            vo.setNickName(user.getNickName() == null || user.getNickName().isBlank()
                    ? "用户" + String.valueOf(user.getId()).substring(String.valueOf(user.getId()).length() - 4)
                    : user.getNickName());
            vo.setIcon(user.getIcon());
            return vo;
        }).collect(Collectors.toList());
    }
}
