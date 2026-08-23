import java.nio.charset.StandardCharsets;

/** Creates the standard response shapes used by request handlers. */
public final class ResponseFactory {
    private final ConfigLoader.Config config;

    public ResponseFactory(ConfigLoader.Config config) {
        this.config = config;
    }

    public HttpResponse error(int status) {
        return FaultPages.response(config, status);
    }

    public HttpResponse redirect(int status, String location) {
        return new HttpResponse(status, "text/plain; charset=utf-8",
                ("redirect: " + location + "\n").getBytes(StandardCharsets.UTF_8))
                .header("Location", location);
    }

    public HttpResponse text(int status, String value) {
        return new HttpResponse(status, "text/plain; charset=utf-8",
                value.getBytes(StandardCharsets.UTF_8));
    }

    public HttpResponse bytes(int status, String contentType, byte[] body) {
        return new HttpResponse(status, contentType, body);
    }
}