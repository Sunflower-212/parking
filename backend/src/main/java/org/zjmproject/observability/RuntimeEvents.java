package org.zjmproject.observability;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Map;

/** Only fixed event names, internal IDs and reason codes belong in these events. */
public final class RuntimeEvents {
    private RuntimeEvents() {}
    public static String requestId() { return MDC.get("requestId"); }
    public static Scope scope(String id) { return new Scope(id); }
    public static final class Scope implements AutoCloseable {
        private final Map<String,String> previous = MDC.getCopyOfContextMap();
        private Scope(String id) {
            MDC.clear();
            if (id != null && id.matches("[a-zA-Z0-9-]{1,64}")) MDC.put("requestId", id);
        }
        @Override public void close() { MDC.clear(); if (previous != null) MDC.setContextMap(previous); }
    }
    public static void event(String action, String result, Object entityId) {
        LoggerFactory.getLogger(RuntimeEvents.class).atInfo().addKeyValue("event",action)
            .addKeyValue("result",result).addKeyValue("entityId",entityId).log("business_event");
    }
    public static void committed(String action, String result, Object entityId) {
        String requestId = requestId();
        Runnable emit = () -> { try (var ignored = scope(requestId)) { event(action,result,entityId); } };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { emit.run(); }
            });
        } else emit.run();
    }
}
