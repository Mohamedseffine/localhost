package webserver.http;

/** Names and reason phrases for responses emitted by the server. */
public final class HttpCodes {
    public static final int OK = 200, CREATED = 201;
    public static final int MOVED_PERMANENTLY = 301, FOUND = 302;
    public static final int TEMPORARY_REDIRECT = 307, PERMANENT_REDIRECT = 308;
    public static final int BAD_REQUEST = 400, FORBIDDEN = 403, NOT_FOUND = 404;
    public static final int METHOD_NOT_ALLOWED = 405, REQUEST_TIMEOUT = 408;
    public static final int PAYLOAD_TOO_LARGE = 413, INTERNAL_SERVER_ERROR = 500;

    private HttpCodes() {}

    public static String reason(int status) {
        if (status == OK) return "OK";
        if (status == CREATED) return "Created";
        if (status == MOVED_PERMANENTLY) return "Moved Permanently";
        if (status == FOUND) return "Found";
        if (status == TEMPORARY_REDIRECT) return "Temporary Redirect";
        if (status == PERMANENT_REDIRECT) return "Permanent Redirect";
        if (status == BAD_REQUEST) return "Bad Request";
        if (status == FORBIDDEN) return "Forbidden";
        if (status == NOT_FOUND) return "Not Found";
        if (status == METHOD_NOT_ALLOWED) return "Method Not Allowed";
        if (status == REQUEST_TIMEOUT) return "Request Timeout";
        if (status == PAYLOAD_TOO_LARGE) return "Payload Too Large";
        return "Internal Server Error";
    }
}