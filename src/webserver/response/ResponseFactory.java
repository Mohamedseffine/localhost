package webserver.response;

import java.nio.charset.StandardCharsets;
import webserver.config.ConfigLoader;
import webserver.http.HttpResponse;

/** Helper for generating HTTP responses. */
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

    public HttpResponse text(int status, String content) {
        return new HttpResponse(status, "text/plain; charset=utf-8",
                content.getBytes(StandardCharsets.UTF_8));
    }

    public HttpResponse bytes(int status, String contentType, byte[] payload) {
        return new HttpResponse(status, contentType, payload);
    }
}