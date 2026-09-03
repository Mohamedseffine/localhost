import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Generates built-in and custom configured HTML error pages.
 */
public final class ErrorPages {
    private ErrorPages() {}

    public static HttpResponse response(int statusCode, Map<Integer, Path> customErrorPages) {
        HttpResponse res = new HttpResponse(statusCode);

        if (customErrorPages != null && customErrorPages.containsKey(statusCode)) {
            Path customPath = customErrorPages.get(statusCode);
            if (customPath != null && Files.isRegularFile(customPath)) {
                try {
                    byte[] content = Files.readAllBytes(customPath);
                    res.body(content);
                    res.setHeader("Content-Type", "text/html; charset=utf-8");
                    res.setHeader("Connection", "close");
                    return res;
                } catch (IOException ignored) {
                    // Fall back to built-in page
                }
            }
        }

        String title = statusCode + " " + HttpResponse.reasonFor(statusCode);
        String html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <style>
                    :root { --bg: #0f172a; --card: #1e293b; --text: #f8fafc; --muted: #94a3b8; --primary: #38bdf8; }
                    body { margin: 0; background: var(--bg); color: var(--text); font-family: system-ui, -apple-system, sans-serif; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
                    .card { background: var(--card); border: 1px solid #334155; border-radius: 16px; padding: 2.5rem; text-align: center; max-width: 480px; box-shadow: 0 20px 25px -5px rgba(0,0,0,0.5); }
                    h1 { font-size: 3.5rem; margin: 0; color: var(--primary); font-weight: 800; }
                    h2 { font-size: 1.25rem; margin: 0.5rem 0 1rem; color: var(--text); }
                    p { color: var(--muted); font-size: 0.95rem; line-height: 1.5; margin-bottom: 1.5rem; }
                    a { display: inline-block; background: var(--primary); color: #0f172a; font-weight: 600; text-decoration: none; padding: 0.6rem 1.2rem; border-radius: 8px; transition: transform 0.15s; }
                    a:hover { transform: scale(1.04); }
                </style>
            </head>
            <body>
                <div class="card">
                    <h1>%d</h1>
                    <h2>%s</h2>
                    <p>The requested operation could not be completed by LocalServer.</p>
                    <a href="/">Back to Home</a>
                </div>
            </body>
            </html>
            """.formatted(title, statusCode, HttpResponse.reasonFor(statusCode));

        res.body(html.getBytes(StandardCharsets.UTF_8));
        res.setHeader("Content-Type", "text/html; charset=utf-8");
        res.setHeader("Connection", "close");
        return res;
    }
}
