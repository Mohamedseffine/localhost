package utils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** In-memory cookie-backed session registry. */
public final class SessionStore {
    private static final long LIFETIME_SECONDS = 3600;
    private final Map<String, Long> entries = new HashMap<>();

    public Result find(String cookieHeader) {
        long now = Instant.now().getEpochSecond();
        entries.entrySet().removeIf(entry -> now - entry.getValue() > LIFETIME_SECONDS);
        String id = readId(cookieHeader);
        if (id != null && entries.containsKey(id)) {
            entries.put(id, now);
            return new Result(null);
        }
        String fresh = UUID.randomUUID().toString();
        entries.put(fresh, now);
        return new Result("session_id=" + fresh
                + "; Max-Age=3600; Path=/; HttpOnly; SameSite=Lax");
    }

    private static String readId(String header) {
        if (header == null) return null;
        for (String item : header.split(";")) {
            int equals = item.indexOf('=');
            if (equals > 0 && item.substring(0, equals).trim().equals("session_id")) {
                return item.substring(equals + 1).trim();
            }
        }
        return null;
    }

    public record Result(String setCookie) {}
}