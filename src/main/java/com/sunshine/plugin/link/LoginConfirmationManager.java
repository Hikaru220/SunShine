package com.sunshine.plugin.link;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class LoginConfirmationManager {

    private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    public String createPending() {
        String token = UUID.randomUUID().toString();
        pending.put(token, new CompletableFuture<>());
        return token;
    }

    public CompletableFuture<Boolean> futureFor(String token) {
        return pending.get(token);
    }

    public void resolve(String token, boolean confirmed) {
        CompletableFuture<Boolean> future = pending.get(token);
        if (future != null) {
            future.complete(confirmed);
        }
    }

    public void remove(String token) {
        pending.remove(token);
    }
}
