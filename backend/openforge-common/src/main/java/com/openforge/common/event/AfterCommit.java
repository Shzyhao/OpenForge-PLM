package com.openforge.common.event;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务边界助手（v1.27 P1#2）：把 best-effort 回退动作（同步 HTTP 通知）推迟到事务提交后执行，
 * 避免事务内同步外呼——下游假死时拖住 DB 连接与回滚窗口（P1#1 超时是第一道防线，这是第二道）。
 * 事务不活跃时立即执行（语义与原地调用一致）。范式先例：DrawingService.publishAfterCommit（v1.24）。
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    /** 事务活跃时 afterCommit 再执行 action；否则立即执行。 */
    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
