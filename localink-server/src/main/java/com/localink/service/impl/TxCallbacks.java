package com.localink.service.impl;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后回调（包内共用）：DB 事实先落定，Redis 增量随后落笔——回滚不留幻影分，
 * 提交后崩溃漏掉的增量由事实源重算兜底（M6-B 统一口径）。
 */
final class TxCallbacks {

    private TxCallbacks() {
    }

    static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
