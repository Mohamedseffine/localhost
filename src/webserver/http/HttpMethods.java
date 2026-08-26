package webserver.http;

import java.util.Set;

/** The request methods supported by the application. */
public final class HttpMethods {
    public static final String GET = "GET";
    public static final String POST = "POST";
    public static final String DELETE = "DELETE";

    private static final Set<String> SUPPORTED = Set.of(GET, POST, DELETE);
    private static final String ALLOW = "GET, POST, DELETE";

    private HttpMethods() {}

    public static boolean supported(String candidate) {
        return SUPPORTED.contains(candidate);
    }

    public static String allowValue() {
        return ALLOW;
    }
}