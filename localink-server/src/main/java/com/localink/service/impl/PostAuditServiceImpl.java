package com.localink.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.localink.entity.Post;
import com.localink.event.PostDeletedEvent;
import com.localink.framework.dfa.SensitiveWordDFA;
import com.localink.mapper.PostMapper;
import com.localink.service.PostAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 帖子异步审核实现（M6-F）：隐性词库复审命中 → 驳回。驳回在事务内置 audit=2 并发布
 * PostDeletedEvent——消费端无外层事务，本方法自带 @Transactional 才能让
 * AFTER_COMMIT 事件监听器（ES 删文档）在提交后触发；驳回语义=内容撤下，
 * 与删帖共享 M6-D 的 DELETE 同步链路（设计时预留兑现）。
 * Feed/热榜/点赞/搜索的 audit=1 读端过滤自 M6-A 起已全线立好，驳回即刻生效。
 */
@Service
@RequiredArgsConstructor
public class PostAuditServiceImpl implements PostAuditService {

    private final PostMapper postMapper;
    private final SensitiveWordDFA auditRiskWordDfa;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public boolean rejectIfRisky(Long postId) {
        Post post = postMapper.selectById(postId);
        // 帖已删（audit 过滤前先判空）/已驳回（重复消息幂等）/未过审态不重复处理
        if (post == null || post.getAuditStatus() == null || post.getAuditStatus() != 1) {
            return false;
        }
        boolean risky = auditRiskWordDfa.contains(post.getTitle() + post.getContent());
        if (!risky) {
            return false;
        }
        postMapper.update(null, new LambdaUpdateWrapper<Post>()
                .eq(Post::getId, postId)
                .eq(Post::getAuditStatus, 1)
                .set(Post::getAuditStatus, 2));
        eventPublisher.publishEvent(new PostDeletedEvent(postId));
        return true;
    }
}
