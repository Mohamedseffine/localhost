package webserver.session;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe, expiring cookie session registry. */
public final class SessionStore {
    private static final long MAX_AGE_SECONDS = 3600;
    private final Map<String, Long> expiry = new ConcurrentHashMap<>();

    public synchronized Result find(String cookieHeader) {
        long now = Instant.now().getEpochSecond();
        expiry.entrySet().removeIf(entry -> entry.getValue() <= now);

        String current = sessionId(cookieHeader);
        Long previousExpiry = current == null ? null : expiry.get(current);
        if (previousExpiry != null && expiry.replace(current, previousExpiry, now + MAX_AGE_SECONDS)) {
            return Result.reused();
        }

        String fresh = UUID.randomUUID().toString();
        expiry.put(fresh, now + MAX_AGE_SECONDS);
        return Result.created("session_id=" + fresh + "; Max-Age=" + MAX_AGE_SECONDS
                + "; Path=/; HttpOnly; SameSite=Lax");
    }

    private static String sessionId(String header) {
        if (header == null) return null;
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "session_id".equalsIgnoreCase(pair.substring(0, eq).trim())) {
                return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public record Result(String setCookie) {
        static Result reused() { return new Result(null); }
        static Result created(String cookie) { return new Result(cookie); }
    }
}