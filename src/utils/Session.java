package utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe HTTP session store with time-to-live expiration.
 */
public final class Session {
    private static final long DEFAULT_TTL_SECONDS = 3600;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    public record Result(String sessionId, String setCookieHeader) {}

    /**
     * Resolves the session from the request cookie header.
     * Reuses the existing valid session without reissuing Set-Cookie, or generates a new one.
     */
    public synchronized Result resolve(String cookieHeader) {
        long now = System.currentTimeMillis() / 1000;
        // Purge expired sessions
        sessions.entrySet().removeIf(e -> e.getValue() <= now);

        String id = Cookie.get(cookieHeader, "session_id");
        if (id != null) {
            Long expiry = sessions.get(id);
            if (expiry != null && expiry > now) {
                // Refresh expiration
                sessions.put(id, now + DEFAULT_TTL_SECONDS);
                return new Result(id, null);
            }
        }

        // Create new session
        String freshId = UUID.randomUUID().toString();
        sessions.put(freshId, now + DEFAULT_TTL_SECONDS);
        String cookieVal = Cookie.build("session_id", freshId, DEFAULT_TTL_SECONDS, "/", true, "Lax");
        return new Result(freshId, cookieVal);
    }
}
