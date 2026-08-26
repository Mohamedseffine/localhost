package webserver.http;

/** Request validation against core protocol invariants. */
public final class RequestPolicy {
    private RequestPolicy() {}

    public static int rejectionCode(HttpRequest request) {
        if (request == null) return HttpCodes.BAD_REQUEST;
        if (HttpMethods.GET.equals(request.method()) && request.body().length > 0) {
            return HttpCodes.BAD_REQUEST;
        }
        if (!HttpMethods.supported(request.method())) {
            return HttpCodes.METHOD_NOT_ALLOWED;
        }
        return 0;
    }
}