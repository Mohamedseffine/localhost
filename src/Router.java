import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import utils.Json;
import utils.Multipart;
import utils.Session;

/**
 * High-performance HTTP request router, static asset server,
 * multipart upload handler, CGI dispatcher, and metrics provider.
 */
public final class Router {
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "DELETE");
    private final ConfigLoader.ServerConfig config;
    private final Session sessionStore = new Session();

    // Metrics counters
    private final long startTime = System.currentTimeMillis();
    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong count2xx = new AtomicLong();
    private final AtomicLong count3xx = new AtomicLong();
    private final AtomicLong count4xx = new AtomicLong();
    private final AtomicLong count5xx = new AtomicLong();

    public Router(ConfigLoader.ServerConfig config) {
        this.config = config;
    }

    public HttpResponse handle(HttpRequest req, ConfigLoader.VirtualServer server, int port) {
        totalRequests.incrementAndGet();
        Session.Result sessionResult = sessionStore.resolve(req.header("cookie"));
        HttpResponse res;

        try {
            res = route(req, server, port);
        } catch (Exception e) {
            System.err.println("Request error: " + e.getMessage());
            res = ErrorPages.response(500, config.errorPages());
        }

        // Apply cookies and virtual host metadata
        if (sessionResult.setCookieHeader() != null) {
            res.header("Set-Cookie", sessionResult.setCookieHeader());
        }
        if (server != null) {
            res.header("X-Server-Name", server.defaultName());
        }

        trackStatus(res.status());
        return res;
    }

    private HttpResponse route(HttpRequest req, ConfigLoader.VirtualServer server, int port) throws IOException {
        String method = req.method().toUpperCase(Locale.ROOT);

        // Check globally supported methods
        if (!SUPPORTED_METHODS.contains(method)) {
            HttpResponse err = ErrorPages.response(405, config.errorPages());
            err.setHeader("Allow", "GET, POST, DELETE");
            return err;
        }

        // Internal bonus metrics endpoint
        if (req.path().equals("/api/metrics") || req.path().equals("/metrics")) {
            return metricsResponse();
        }

        // Find matching route by longest prefix
        ConfigLoader.RouteConfig matchedRoute = null;
        for (ConfigLoader.RouteConfig r : config.routes()) {
            if (matchesRoute(req.path(), r.path())) {
                matchedRoute = r;
                break;
            }
        }

        if (matchedRoute == null) {
            return ErrorPages.response(404, config.errorPages());
        }

        // Validate allowed HTTP method for this route
        if (!matchedRoute.methods().contains(method)) {
            HttpResponse err = ErrorPages.response(405, config.errorPages());
            err.setHeader("Allow", String.join(", ", matchedRoute.methods()));
            return err;
        }

        // Handle configured redirects
        if (matchedRoute.redirect() != null && !matchedRoute.redirect().isEmpty()) {
            HttpResponse red = new HttpResponse(matchedRoute.redirectStatus());
            red.setHeader("Location", matchedRoute.redirect());
            red.body(("Redirecting to " + matchedRoute.redirect() + "\n").getBytes(StandardCharsets.UTF_8));
            return red;
        }

        // Dispatch method
        return switch (method) {
            case "GET" -> handleGet(req, matchedRoute, server, port);
            case "POST" -> handlePost(req, matchedRoute, server, port);
            case "DELETE" -> handleDelete(req, matchedRoute);
            default -> {
                HttpResponse err = ErrorPages.response(405, config.errorPages());
                err.setHeader("Allow", String.join(", ", matchedRoute.methods()));
                yield err;
            }
        };
    }

    private HttpResponse handleGet(
            HttpRequest req,
            ConfigLoader.RouteConfig route,
            ConfigLoader.VirtualServer server,
            int port
    ) throws IOException {
        Path target = resolvePath(req.path(), route);
        if (target == null) return ErrorPages.response(403, config.errorPages());
        if (!Files.exists(target)) return ErrorPages.response(404, config.errorPages());

        // Directory handling
        if (Files.isDirectory(target)) {
            if (!req.path().endsWith("/")) {
                HttpResponse red = new HttpResponse(301);
                red.setHeader("Location", req.path() + "/");
                return red;
            }

            if (route.defaultFile() != null) {
                Path defFile = target.resolve(route.defaultFile()).normalize();
                if (Files.isRegularFile(defFile)) {
                    return serveFile(defFile);
                }
            }

            if (route.directoryListing()) {
                return renderDirectoryListing(req.path(), target);
            }

            return ErrorPages.response(403, config.errorPages());
        }

        // CGI execution
        if (route.cgi()) {
            byte[] cgiOut = CGIHandler.execute(config, target, req, req.queryString(), req.path(), server, port);
            HttpResponse res = new HttpResponse(200);
            res.setHeader("Content-Type", "text/plain; charset=utf-8");
            res.body(cgiOut);
            return res;
        }

        // Static file serving
        return serveFile(target);
    }

    private HttpResponse handlePost(
            HttpRequest req,
            ConfigLoader.RouteConfig route,
            ConfigLoader.VirtualServer server,
            int port
    ) throws IOException {
        Path target = resolvePath(req.path(), route);
        if (target == null) return ErrorPages.response(403, config.errorPages());

        String contentType = req.header("content-type");
        boolean isMultipart = contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");

        // If route is configured as CGI
        if (route.cgi()) {
            if (!Files.isRegularFile(target)) return ErrorPages.response(400, config.errorPages());
            String data = isMultipart ? "multipart" : new String(req.body(), StandardCharsets.UTF_8);
            byte[] cgiOut = CGIHandler.execute(config, target, req, data, req.path(), server, port);
            HttpResponse res = new HttpResponse(200);
            res.setHeader("Content-Type", "text/plain; charset=utf-8");
            res.body(cgiOut);
            return res;
        }

        // Multipart file upload
        if (isMultipart) {
            try {
                List<Multipart.Part> parts = Multipart.parse(req.body(), contentType);
                Map<String, String> uploadedFiles = new LinkedHashMap<>();

                for (Multipart.Part p : parts) {
                    if (p.filename() != null && !p.filename().isBlank()) {
                        String ext = getFileExtension(p.filename());
                        String saveName = UUID.randomUUID().toString() + ext;
                        Path dest = config.uploadDir().resolve(saveName).normalize();
                        if (!dest.startsWith(config.uploadDir())) {
                            return ErrorPages.response(403, config.errorPages());
                        }
                        Files.write(dest, p.data(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        String relativePath = config.rootDir().relativize(dest).toString().replace('\\', '/');
                        uploadedFiles.put(p.name(), relativePath);
                    } else {
                        uploadedFiles.put(p.name(), new String(p.data(), StandardCharsets.UTF_8));
                    }
                }

                String jsonResponse = Json.stringify(uploadedFiles);
                HttpResponse res = new HttpResponse(201);
                res.setHeader("Content-Type", "application/json; charset=utf-8");
                res.body(jsonResponse.getBytes(StandardCharsets.UTF_8));
                return res;
            } catch (IllegalArgumentException e) {
                return ErrorPages.response(400, config.errorPages());
            }
        }

        // Non-multipart POST
        HttpResponse res = new HttpResponse(200);
        res.setHeader("Content-Type", "text/plain; charset=utf-8");
        res.body(new byte[0]);
        return res;
    }

    private HttpResponse handleDelete(HttpRequest req, ConfigLoader.RouteConfig route) throws IOException {
        Path target = resolvePath(req.path(), route);
        if (target == null) return ErrorPages.response(403, config.errorPages());
        if (!Files.exists(target) || Files.isDirectory(target)) {
            return ErrorPages.response(404, config.errorPages());
        }

        Files.delete(target);
        HttpResponse res = new HttpResponse(200);
        res.setHeader("Content-Type", "text/plain; charset=utf-8");
        res.body("deleted\n".getBytes(StandardCharsets.UTF_8));
        return res;
    }

    private Path resolvePath(String requestPath, ConfigLoader.RouteConfig route) {
        String sub = requestPath.startsWith(route.path()) ? requestPath.substring(route.path().length()) : "";
        while (sub.startsWith("/")) sub = sub.substring(1);

        Path routeRoot = config.rootDir().resolve(route.root()).normalize();
        Path candidate = routeRoot.resolve(sub).normalize();

        if (!candidate.startsWith(config.rootDir()) || !candidate.startsWith(routeRoot)) {
            return null; // Path traversal attempt
        }
        return candidate;
    }

    private boolean matchesRoute(String requestPath, String routePath) {
        if (routePath.equals("/")) return true;
        if (routePath.endsWith("/")) {
            return requestPath.startsWith(routePath) || requestPath.equals(routePath.substring(0, routePath.length() - 1));
        }
        return requestPath.equals(routePath) || requestPath.startsWith(routePath + "/");
    }

    private HttpResponse serveFile(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        HttpResponse res = new HttpResponse(200);
        res.setHeader("Content-Type", mimeType(file));
        res.setHeader("Content-Length", String.valueOf(data.length));
        res.body(data);
        return res;
    }

    private HttpResponse renderDirectoryListing(String reqPath, Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>Directory: ").append(escapeHtml(reqPath)).append("</title>")
          .append("<style>body{font-family:system-ui,sans-serif;margin:2rem;background:#0f172a;color:#f8fafc;}")
          .append("a{color:#38bdf8;text-decoration:none;font-size:1.1rem;padding:0.4rem 0;display:inline-block;}")
          .append("a:hover{text-decoration:underline;}ul{list-style:none;padding:0;}li{border-bottom:1px solid #334155;}")
          .append("</style></head><body>")
          .append("<h1>Index of ").append(escapeHtml(reqPath)).append("</h1><ul>");

        try (var stream = Files.list(dir)) {
            List<Path> entries = stream.sorted().toList();
            for (Path p : entries) {
                String name = p.getFileName().toString();
                String href = reqPath + (reqPath.endsWith("/") ? "" : "/") + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                sb.append("<li><a href=\"").append(href).append("\">").append(escapeHtml(name)).append(Files.isDirectory(p) ? "/" : "").append("</a></li>");
            }
        }
        sb.append("</ul></body></html>");

        byte[] html = sb.toString().getBytes(StandardCharsets.UTF_8);
        HttpResponse res = new HttpResponse(200);
        res.setHeader("Content-Type", "text/html; charset=utf-8");
        res.setHeader("Content-Length", String.valueOf(html.length));
        res.body(html);
        return res;
    }

    private HttpResponse metricsResponse() {
        long uptimeSec = (System.currentTimeMillis() - startTime) / 1000;
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory() / (1024 * 1024);
        long freeMem = rt.freeMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("server", "LocalServer 2.0 (Java NIO)");
        metrics.put("uptime_seconds", uptimeSec);
        metrics.put("total_requests", totalRequests.get());
        metrics.put("status_2xx", count2xx.get());
        metrics.put("status_3xx", count3xx.get());
        metrics.put("status_4xx", count4xx.get());
        metrics.put("status_5xx", count5xx.get());
        metrics.put("memory_used_mb", usedMem);
        metrics.put("memory_total_mb", totalMem);
        metrics.put("available_processors", rt.availableProcessors());

        HttpResponse res = new HttpResponse(200);
        res.setHeader("Content-Type", "application/json; charset=utf-8");
        res.body(Json.stringify(metrics).getBytes(StandardCharsets.UTF_8));
        return res;
    }

    private void trackStatus(int code) {
        if (code >= 200 && code < 300) count2xx.incrementAndGet();
        else if (code >= 300 && code < 400) count3xx.incrementAndGet();
        else if (code >= 400 && code < 500) count4xx.incrementAndGet();
        else if (code >= 500) count5xx.incrementAndGet();
    }

    private static String mimeType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".txt") || name.endsWith(".md")) return "text/plain; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private static String getFileExtension(String fn) {
        int dot = fn.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = fn.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("^\\.[a-z0-9]{1,10}$") ? ext : "";
    }

    private static String escapeHtml(String str) {
        return str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
