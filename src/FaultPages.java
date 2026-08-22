import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Builds responses for failures using the configured page set. */
public final class FaultPages {
    private FaultPages() {}

    public static HttpResponse response(ConfigLoader.Config config, int code) {
        return new HttpResponse(code, "text/html; charset=utf-8", content(config, code));
    }

    private static byte[] content(ConfigLoader.Config config, int code) {
        try {
            Path page = config.errorPages().get(code);
            if (page != null) return Files.readAllBytes(page);
        } catch (Exception ignored) {
            // The plain response below is the last-resort error page.
        }
        return (code + " " + HttpCodes.reason(code) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}