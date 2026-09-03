package utils;

/**
 * Lightweight cookie parsing and formatting utility.
 */
public final class Cookie {
    private Cookie() {}

    /**
     * Extracts the value of a named cookie from the 'Cookie' header.
     */
    public static String get(String cookieHeader, String name) {
        if (cookieHeader == null || name == null || cookieHeader.isEmpty()) {
            return null;
        }
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq).trim();
                if (k.equalsIgnoreCase(name)) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * Formats a Set-Cookie header attribute string.
     */
    public static String build(String name, String value, long maxAge, String path, boolean httpOnly, String sameSite) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value);
        if (maxAge >= 0) {
            sb.append("; Max-Age=").append(maxAge);
        }
        if (path != null && !path.isEmpty()) {
            sb.append("; Path=").append(path);
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        if (sameSite != null && !sameSite.isEmpty()) {
            sb.append("; SameSite=").append(sameSite);
        }
        return sb.toString();
    }
}
