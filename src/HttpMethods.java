import java.util.List;

/** Supported request methods and the server-wide Allow value. */
public final class HttpMethods {
    private static final List<String> SUPPORTED = List.of("GET", "POST", "DELETE");

    private HttpMethods() {}

    public static boolean supported(String method) {
        return SUPPORTED.contains(method);
    }

    public static String allowValue() {
        return String.join(", ", SUPPORTED);
    }
}