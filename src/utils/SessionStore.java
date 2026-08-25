package utils;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory session registry. */
public final class SessionStore {
    private static final long TTL = 3600L;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public synchronized Result find(String cookieHeader) {
        long now = Instant.now().getEpochSecond();
        sessions.entrySet().removeIf(e -> now - e.getValue() > TTL);

        String id = extractId(cookieHeader);
        if (id != null && sessions.containsKey(id)) {
            sessions.put(id, now);
            return new Result(null);
        }

        String fresh = UUID.randomUUID().toString();
        sessions.put(fresh, now);
        return new Result("session_id=" + fresh + "; Max-Age=" + TTL + "; Path=/; HttpOnly; SameSite=Lax");
    }

    private static String extractId(String header) {
        if (header == null) return null;
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equalsIgnoreCase("session_id")) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public record Result(String setCookie) {}
}