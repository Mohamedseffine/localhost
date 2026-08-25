import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Error response generator with configured page support. */
public final class FaultPages {
    private FaultPages() {}

    public static HttpResponse response(ConfigLoader.Config config, int code) {
        return new HttpResponse(code, "text/html; charset=utf-8", body(config, code));
    }

    private static byte[] body(ConfigLoader.Config config, int code) {
        if (config != null && config.errorPages() != null) {
            Path page = config.errorPages().get(code);
            if (page != null) {
                try {
                    return Files.readAllBytes(page);
                } catch (Exception ignored) {}
            }
        }
        return (code + " " + HttpCodes.reason(code) + "\n").getBytes(StandardCharsets.UTF_8);
    }
}