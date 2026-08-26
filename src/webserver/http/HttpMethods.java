package webserver.http;

import java.util.Set;

/** HTTP method constants and validation. */
public final class HttpMethods {
    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final String DELETE = "DELETE";

    private static final Set<String> SUPPORTED = Set.of(GET, POST, DELETE);

    private HttpMethods() {}

    public static boolean supported(String method) {
        return method != null && SUPPORTED.contains(method);
    }

    public static String allowValue() {
        return "GET, POST, DELETE";
    }
}