package webserver.http;

/** HTTP status code metadata and RFC 9110 reason phrases. */
public final class HttpCodes {
    public static final int OK = 200;
    public static final int CREATED = 201;
    public static final int MOVED_PERMANENTLY = 301;
    public static final int FOUND = 302;
    public static final int TEMPORARY_REDIRECT = 307;
    public static final int PERMANENT_REDIRECT = 308;
    public static final int BAD_REQUEST = 400;
    public static final int FORBIDDEN = 403;
    public static final int NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405;
    public static final int REQUEST_TIMEOUT = 408;
    public static final int PAYLOAD_TOO_LARGE = 413;
    public static final int INTERNAL_SERVER_ERROR = 500;

    private HttpCodes() {}

    public static String reason(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 413 -> "Payload Too Large";
            default -> "Internal Server Error";
        };
    }
}