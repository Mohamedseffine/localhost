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

/** HTTP request router and handler. */
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
            int policy = RequestPolicy.rejectionCode(request);
            if (policy == 400) {
                response = responses.error(400);
            } else if (policy == 405) {
                response = responses.error(405).header("Allow", HttpMethods.allowValue());
            } else {
                RouteMatcher.Match match = matcher.find(request.target());
                RouteMatcher.Target target = match.target();
                ConfigLoader.Route route = match.route();

                if (route == null) {
                    response = responses.error(404);
                } else if (!route.methods().contains(request.method())) {
                    response = responses.error(405).header("Allow", String.join(", ", route.methods()));
                } else if (route.redirect() != null) {
                    response = responses.redirect(route.redirectStatus(), route.redirect());
                } else {
                    response = switch (request.method()) {
                        case HttpMethods.GET -> get(target, route);
                        case HttpMethods.POST -> post(request, target, route);
                        case HttpMethods.DELETE -> delete(target, route);
                        default -> responses.error(405);
                    };
                }
            }
        } catch (Forbidden e) {
            response = responses.error(403);
        } catch (BadRequest | IllegalArgumentException e) {
            response = responses.error(400);
        } catch (Exception e) {
            System.err.println("Router error: " + e.getMessage());
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
            byte[] output = CGIHandler.execute(config, file.path, target.query(), target.path());
            return responses.bytes(200, "text/plain; charset=utf-8", output);
        }
        return new HttpResponse(200, mime(file.path), Files.readAllBytes(file.path));
    }

    private HttpResponse post(HttpRequest req, RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        FileResult file = resolve(target.path(), route, true);
        if (file.redirect != null) return responses.redirect(301, file.redirect);
        if (file.status != 200) return responses.error(file.status);

        String ct = req.header("content-type");
        boolean multipart = ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
        String data = multipart ? multipart(req.body(), ct) : new String(req.body(), StandardCharsets.UTF_8);

        if (route.cgi()) {
            if (Files.isDirectory(file.path)) throw new BadRequest();
            checkCgi(file.path);
            byte[] output = CGIHandler.execute(config, file.path, data, target.path());
            return responses.bytes(200, "text/plain; charset=utf-8", output);
        }
        if (multipart) {
            return responses.bytes(201, "application/json; charset=utf-8", data.getBytes(StandardCharsets.UTF_8));
        }
        return responses.bytes(200, "text/plain; charset=utf-8", new byte[0]);
    }

    private HttpResponse delete(RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        FileResult file = resolve(target.path(), route, false);
        if (file.redirect != null) return responses.redirect(301, file.redirect);
        if (file.status != 200 || !Files.isRegularFile(file.path)) return responses.error(404);
        Files.delete(file.path);
        return responses.text(200, "deleted\n");
    }

    private String multipart(byte[] body, String ct) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        final List<MultipartParser.Part> parts;
        try {
            parts = MultipartParser.read(body, ct);
        } catch (IllegalArgumentException e) {
            throw new BadRequest();
        }

        for (MultipartParser.Part part : parts) {
            if (part.filename() == null || part.filename().isEmpty()) {
                values.put(part.name(), new String(part.content(), StandardCharsets.UTF_8));
            } else {
                String ext = fileExt(part.filename());
                Path out = config.uploads().resolve(UUID.randomUUID() + ext).normalize();
                if (!out.startsWith(config.uploads())) throw new Forbidden();
                Files.write(out, part.content(), StandardOpenOption.CREATE_NEW);
                values.put(part.name(), config.root().relativize(out).toString().replace('\\', '/'));
            }
        }
        return json(values);
    }

    private FileResult resolve(String reqPath, ConfigLoader.Route route, boolean allowDir) throws IOException {
        String sub = route.path().equals("/") ? reqPath.substring(1)
                : (route.path().endsWith("/") && reqPath.equals(route.path().substring(0, route.path().length() - 1))) ? ""
                : reqPath.substring(route.path().length());
        while (sub.startsWith("/")) sub = sub.substring(1);

        Path routeRoot = config.root().resolve(route.root()).normalize();
        Path path = routeRoot.resolve(sub).normalize();
        if (!routeRoot.startsWith(config.root()) || !path.startsWith(routeRoot)) throw new Forbidden();
        if (!Files.exists(path)) return new FileResult(path, 404, null);

        Path realRoot = routeRoot.toRealPath();
        Path real = path.toRealPath();
        if (!realRoot.startsWith(config.root()) || !real.startsWith(realRoot)) throw new Forbidden();

        if (Files.isDirectory(real)) {
            if (!reqPath.endsWith("/")) return new FileResult(real, 301, reqPath + "/");
            if (allowDir) return new FileResult(real, 200, null);
            if (route.defaultFile() != null) {
                Path def = real.resolve(route.defaultFile()).normalize();
                if (def.startsWith(config.root()) && Files.isRegularFile(def)) {
                    Path realDef = def.toRealPath();
                    if (!realDef.startsWith(real)) throw new Forbidden();
                    return new FileResult(realDef, 200, null);
                }
            }
            return route.directoryListing() ? new FileResult(real, 200, null) : new FileResult(real, 403, null);
        }
        return new FileResult(real, 200, null);
    }

    private void checkCgi(Path script) {
        String name = script.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ext.equals(config.cgi().extension())) throw new BadRequest();
    }

    private static HttpResponse listing(String reqPath, Path dir) throws IOException {
        StringBuilder html = new StringBuilder("<!doctype html><title>Files</title><h1>Files</h1><ul>");
        try (var stream = Files.list(dir)) {
            for (Path entry : stream.sorted().toList()) {
                String name = entry.getFileName().toString();
                String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
                html.append("<li><a href=\"").append(reqPath).append(encoded).append("\">")
                        .append(esc(name)).append("</a></li>");
            }
        }
        html.append("</ul>");
        return new HttpResponse(200, "text/html; charset=utf-8", html.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String mime(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static String fileExt(String filename) {
        String base = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        int dot = base.lastIndexOf('.');
        if (dot < 0) return "";
        String ext = base.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("^[a-z0-9]{1,10}$") ? "." + ext : "";
    }

    private static String json(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escJson(e.getKey())).append("\":\"")
                    .append(escJson(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record FileResult(Path path, int status, String redirect) {}
    private static final class BadRequest extends RuntimeException { private static final long serialVersionUID = 1L; }
    private static final class Forbidden extends RuntimeException { private static final long serialVersionUID = 1L; }
}
