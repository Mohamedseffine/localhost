package webserver.http;

/** Small gate for requests that cannot reach route selection. */
public final class RequestPolicy {
    private RequestPolicy() {}

    public static int rejectionCode(HttpRequest request) {
        if (request == null) return HttpCodes.BAD_REQUEST;
        if (request.body().length != 0 && HttpMethods.GET.equals(request.method())) {
            return HttpCodes.BAD_REQUEST;
        }
        return HttpMethods.supported(request.method()) ? 0 : HttpCodes.METHOD_NOT_ALLOWED;
    }
}