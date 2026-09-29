package com.localink.mq;

import com.localink.service.PostSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 帖子→ES 同步消费者（M6-D）：固定消费组（单写语义——多实例分区竞争，不重复同步）。
 * UPSERT：查事实源组装文档 upsert（天然幂等，重投/乱序后到覆盖）；帖不存在或未过审=跳过
 * （发帖后被秒删/驳回的窗口），防御毒消息不无限重投。DELETE：按 id 删文档，404 视为已删。
 */
@Slf4j
@Component
public class PostSearchConsumer extends AbstractKafkaConsumer<PostSearchMessage> {

    private final PostSearchService postSearchService;

    public PostSearchConsumer(PostSearchService postSearchService) {
        super(PostSearchMessage.class);
        this.postSearchService = postSearchService;
    }

    @KafkaListener(
            topics = MqTopics.POST_SEARCH_SYNC,
            groupId = "localink-server-post-search",
            containerFactory = "postSearchContainerFactory")
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected void doConsume(PostSearchMessage payload, MessageEnvelope<PostSearchMessage> envelope) {
        switch (payload.event()) {
            case UPSERT -> {
                boolean indexed = postSearchService.indexPost(payload.postId());
                if (!indexed) {
                    log.warn("帖子不可索引(不存在/未过审), 跳过: postId={}", payload.postId());
                }
            }
            case DELETE -> postSearchService.deletePostFromIndex(payload.postId());
        }
    }
}
