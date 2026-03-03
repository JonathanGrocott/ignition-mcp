package com.jg.ignition.mcp.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class McpSessionManager {

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public String createSession(String actorFingerprint, String transportMode, int maxConcurrentSessions) {
        if (sessions.size() >= maxConcurrentSessions) {
            throw new IllegalStateException("Maximum concurrent sessions reached");
        }

        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new SessionState(sessionId, actorFingerprint, transportMode));
        return sessionId;
    }

    public boolean isOwnedBy(String sessionId, String actorFingerprint) {
        SessionState state = sessions.get(sessionId);
        return state != null && state.actorFingerprint().equals(actorFingerprint);
    }

    public void closeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    public void enqueueEvent(String sessionId, String eventPayload) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.events().offer(eventPayload);
        }
    }

    public List<String> drainEvents(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        String value;
        while ((value = state.events().poll()) != null) {
            out.add(value);
        }
        return out;
    }

    public record SessionState(
        String sessionId,
        String actorFingerprint,
        String transportMode,
        Instant createdAt,
        ConcurrentLinkedQueue<String> events
    ) {
        SessionState(String sessionId, String actorFingerprint, String transportMode) {
            this(sessionId, actorFingerprint, transportMode, Instant.now(), new ConcurrentLinkedQueue<>());
        }
    }
}
