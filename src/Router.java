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
import java.util.UUID;
import utils.SessionStore;

/** Applies configured routes and implements GET, POST, DELETE, uploads, and CGI. */
public final class Router {
    private final ConfigLoader.Config config;
    private final SessionStore sessions = new SessionStore();
    private final RouteMatcher matcher;
    private final ResponseFactory responses;

    public Router(ConfigLoader.Config config) {
        this.config = config;
        this.matcher = new RouteMatcher(config);
        this.responses = new ResponseFactory(config);
    }

    public HttpResponse handle(HttpRequest request, ConfigLoader.VirtualServer server) {
        SessionStore.Result session = sessions.find(request.header("cookie"));
        HttpResponse response;
        try {
            int policyFailure = RequestPolicy.rejectionCode(request);
            if (policyFailure == 400) {
                response = responses.error(policyFailure);
            } else if (policyFailure == 405) {
                response = responses.error(policyFailure).header("Allow", HttpMethods.allowValue());
            } else {
                RouteMatcher.Match match = matcher.find(request.target());
                RouteMatcher.Target target = match.target();
                ConfigLoader.Route route = match.route();
                if (route == null) {
                    response = responses.error(404);
                } else if (!route.methods().contains(request.method())) {
                    response = responses.error(405)
                            .header("Allow", String.join(", ", route.methods()));
                } else if (route.redirect() != null) {
                    response = responses.redirect(route.redirectStatus(), route.redirect());
                } else {
                    response = switch (request.method()) {
                        case "GET" -> get(target, route);
                        case "POST" -> post(request, target, route);
                        case "DELETE" -> delete(target, route);
                        default -> responses.error(405);
                    };
                }
            }
        } catch (Forbidden error) {
            response = responses.error(403);
        } catch (BadRequest error) {
            response = responses.error(400);
        } catch (IllegalArgumentException error) {
            response = responses.error(400);
        } catch (Exception error) {
            System.err.println("Request failed: " + error.getMessage());
            response = responses.error(500);
        }
        if (session.setCookie() != null) response.header("Set-Cookie", session.setCookie());
        response.header("X-Server-Name", server.name());
        return response;
    }

    private HttpResponse get(RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        FileResult file = resolve(target.path(), route, false);
        if (file.redirect != null) return responses.redirect(301, file.redirect);
        if (file.status != 200) return responses.error(file.status);
        if (Files.isDirectory(file.path)) return listing(target.path(), file.path);
        if (route.cgi()) {
            checkCgi(file.path);
                    return responses.bytes(200, "text/plain; charset=utf-8",
                    CGIHandler.execute(config, file.path, target.query(), target.path()));
        }
        return new HttpResponse(200, mime(file.path), Files.readAllBytes(file.path));
    }

    private HttpResponse post(HttpRequest request, RouteMatcher.Target target,
                              ConfigLoader.Route route) throws IOException {
        FileResult file = resolve(target.path(), route, true);
        if (file.redirect != null) return responses.redirect(301, file.redirect);
        if (file.status != 200) return responses.error(file.status);

        String contentType = request.header("content-type");
        String data;
        boolean multipart = contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
        if (multipart) data = multipart(request.body(), contentType);
        else data = new String(request.body(), StandardCharsets.UTF_8);

        if (route.cgi()) {
            if (Files.isDirectory(file.path)) throw new BadRequest();
            checkCgi(file.path);
            return responses.bytes(200, "text/plain; charset=utf-8",
                    CGIHandler.execute(config, file.path, data, target.path()));
        }
        if (multipart) {
            return responses.bytes(201, "application/json; charset=utf-8",
                    data.getBytes(StandardCharsets.UTF_8));
        }
        return responses.bytes(200, "text/plain; charset=utf-8", new byte[0]);
    }

    private HttpResponse delete(RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        FileResult file = resolve(target.path(), route, false);
        if (file.redirect != null) return responses.redirect(301, file.redirect);
        if (file.status != 200 || !Files.isRegularFile(file.path)) {
            return responses.error(404);
        }
        Files.delete(file.path);
        return responses.text(200, "deleted\n");
    }

    private String multipart(byte[] body, String contentType) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        final List<MultipartParser.Part> parts;
        try {
            parts = MultipartParser.read(body, contentType);
        } catch (IllegalArgumentException error) {
            throw new BadRequest();
        }
        for (MultipartParser.Part part : parts) {
            if (part.filename() == null || part.filename().isEmpty()) {
                values.put(part.name(), new String(part.content(), StandardCharsets.UTF_8));
            } else {
                String suffix = suffix(part.filename());
                Path output = config.uploads().resolve(UUID.randomUUID() + suffix).normalize();
                if (!output.startsWith(config.uploads())) throw new Forbidden();
                Files.write(output, part.content(), StandardOpenOption.CREATE_NEW);
                values.put(part.name(), config.root().relativize(output).toString().replace('\\', '/'));
            }
        }
        return json(values);
    }

    private FileResult resolve(String requestPath, ConfigLoader.Route route,
                               boolean allowDirectory) throws IOException {
        String relative;
        if (route.path().equals("/")) {
            relative = requestPath.substring(1);
        } else if (route.path().endsWith("/")
                && requestPath.equals(route.path().substring(0, route.path().length() - 1))) {
            relative = "";
        } else {
            relative = requestPath.substring(route.path().length());
        }
        while (relative.startsWith("/")) relative = relative.substring(1);
        Path routeRoot = config.root().resolve(route.root()).normalize();
        Path path = routeRoot.resolve(relative).normalize();
        if (!routeRoot.startsWith(config.root()) || !path.startsWith(routeRoot)) throw new Forbidden();
        if (!Files.exists(path)) return new FileResult(path, 404, null);

        Path realRoot = routeRoot.toRealPath();
        Path real = path.toRealPath();
        if (!realRoot.startsWith(config.root()) || !real.startsWith(realRoot)) throw new Forbidden();
        if (Files.isDirectory(real)) {
            if (!requestPath.endsWith("/")) return new FileResult(real, 301, requestPath + "/");
            if (allowDirectory) return new FileResult(real, 200, null);
            if (route.defaultFile() != null) {
                Path index = real.resolve(route.defaultFile()).normalize();
                if (index.startsWith(config.root()) && Files.isRegularFile(index)) {
                    Path realIndex = index.toRealPath();
                    if (!realIndex.startsWith(real)) throw new Forbidden();
                    return new FileResult(realIndex, 200, null);
                }
            }
            return route.directoryListing() ? new FileResult(real, 200, null)
                    : new FileResult(real, 403, null);
        }
        return new FileResult(real, 200, null);
    }

    private void checkCgi(Path script) {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals(config.cgi().extension())) throw new BadRequest();
    }

    private static HttpResponse listing(String requestPath, Path directory) throws IOException {
        StringBuilder html = new StringBuilder("<!doctype html><title>Files</title><h1>Files</h1><ul>");
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                html.append("<li><a href=\"").append(requestPath).append(encoded).append("\">")
                        .append(escape(name)).append("</a></li>");
            }
        }
        html.append("</ul>");
        return new HttpResponse(200, "text/html; charset=utf-8",
                html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String mime(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static String suffix(String filename) {
        String name = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "";
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,10}") ? "." + extension : "";
    }

    private static String json(Map<String, String> values) {
        StringBuilder result = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) result.append(',');
            first = false;
            result.append('"').append(jsonText(entry.getKey())).append("\":\"")
                    .append(jsonText(entry.getValue())).append('"');
        }
        return result.append('}').toString();
    }

    private static String jsonText(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record FileResult(Path path, int status, String redirect) {}
    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static final class Forbidden extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
