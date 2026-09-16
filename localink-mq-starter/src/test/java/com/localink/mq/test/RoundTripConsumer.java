package com.localink.mq.test;

import com.localink.mq.AbstractKafkaConsumer;
import com.localink.mq.MessageEnvelope;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@Component
class RoundTripConsumer extends AbstractKafkaConsumer<TestPayload> {

    final List<TestPayload> received = new CopyOnWriteArrayList<>();
    final List<MessageEnvelope<TestPayload>> envelopes = new CopyOnWriteArrayList<>();
    final CountDownLatch latch = new CountDownLatch(1);

    RoundTripConsumer() {
        super(TestPayload.class);
    }

    @KafkaListener(topics = "${m37.topics.rt}", groupId = "m37-rt-${random.uuid}")
    void onMessage(String value, Acknowledgment ack) {
        dispatch(value, ack);
    }

    @Override
    protected void doConsume(TestPayload payload, MessageEnvelope<TestPayload> envelope) {
        received.add(payload);
        envelopes.add(envelope);
        latch.countDown();
    }
}
