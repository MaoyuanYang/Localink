package com.localink.mq;

import com.localink.event.PostCreatedEvent;
import com.localink.event.PostDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 帖子→ES 同步生产者（M6-D）：订阅发帖/删帖事实事件，事务提交后发 Kafka——
 * 事务内发消息=消息先于提交可见，消费端查 DB 会查到旧态/空。
 * fire-and-forget：sendAsync 失败仅告警不阻塞业务（ES 是可重算派生视图，rebuildAll 兜底）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostSearchProducer {

    private final MessageProducer messageProducer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostCreated(PostCreatedEvent event) {
        send(event.postId(), PostSyncEvent.UPSERT);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostDeleted(PostDeletedEvent event) {
        send(event.postId(), PostSyncEvent.DELETE);
    }

    private void send(Long postId, PostSyncEvent syncEvent) {
        messageProducer.sendAsync(MqTopics.POST_SEARCH_SYNC, String.valueOf(postId),
                        new PostSearchMessage(postId, syncEvent))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("帖子 ES 同步消息发送失败, postId={}, event={}: {}",
                                postId, syncEvent, ex.getMessage());
                    }
                });
    }
}
