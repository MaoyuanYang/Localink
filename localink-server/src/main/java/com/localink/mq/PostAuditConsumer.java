package com.localink.mq;

import com.localink.service.PostAuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 帖子异步审核消费者（M6-F）：固定消费组（单写语义）。复审通过/帖已删/重复消息
 * 均为正常路径（跳过 ack）；审核服务异常 rethrow 走默认错误处理器重投。
 */
@Slf4j
@Component
public class PostAuditConsumer extends AbstractKafkaConsumer<PostAuditMessage> {

    private final PostAuditService postAuditService;

    public PostAuditConsumer(PostAuditService postAuditService) {
        super(PostAuditMessage.class);
        this.postAuditService = postAuditService;
    }

    @KafkaListener(
            topics = MqTopics.POST_AUDIT,
            groupId = "localink-server-post-audit")
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected void doConsume(PostAuditMessage payload, MessageEnvelope<PostAuditMessage> envelope) {
        boolean rejected = postAuditService.rejectIfRisky(payload.postId());
        if (rejected) {
            log.info("异步复审驳回, postId={}", payload.postId());
        }
    }
}
