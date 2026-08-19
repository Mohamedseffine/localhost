/** Applies protocol rules that are independent of route configuration. */
public final class RequestPolicy {
    private RequestPolicy() {}

    public static int rejectionCode(HttpRequest request) {
        if (request.method().equals("GET") && request.body().length > 0) return 400;
        return HttpMethods.supported(request.method()) ? 0 : 405;
    }
}