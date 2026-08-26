package webserver.routing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import webserver.config.ConfigLoader;
import webserver.delivery.CGIHandler;
import webserver.delivery.ResourceService;
import webserver.delivery.ResourceService.BadRequest;
import webserver.delivery.ResourceService.Forbidden;
import webserver.http.HttpMethods;
import webserver.http.HttpRequest;
import webserver.http.HttpResponse;
import webserver.http.RequestPolicy;
import webserver.response.ResponseFactory;
import webserver.session.SessionStore;

/** HTTP request router and handler. */
public final class Router {
    private final ConfigLoader.Config config;
    private final SessionStore sessions = new SessionStore();
    private final RouteMatcher matcher;
    private final ResponseFactory responses;
    private final ResourceService resources;

    public Router(ConfigLoader.Config config) {
        this.config = config;
        this.matcher = new RouteMatcher(config);
        this.responses = new ResponseFactory(config);
        this.resources = new ResourceService(config, responses);
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
        ResourceService.Resource file = resources.resolve(target.path(), route, false);
        HttpResponse statusResponse = resourceStatus(file);
        if (statusResponse != null) return statusResponse;
        if (Files.isDirectory(file.path())) return resources.directoryListing(target.path(), file.path());
        if (route.cgi()) {
            resources.verifyCgi(file.path());
            byte[] output = CGIHandler.execute(config, file.path(), target.query(), target.path());
            return responses.bytes(200, "text/plain; charset=utf-8", output);
        }
        return new HttpResponse(200, ResourceService.contentType(file.path()), Files.readAllBytes(file.path()));
    }

    private HttpResponse post(HttpRequest req, RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        ResourceService.Resource file = resources.resolve(target.path(), route, true);
        HttpResponse statusResponse = resourceStatus(file);
        if (statusResponse != null) return statusResponse;

        String ct = req.header("content-type");
        boolean multipart = ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
        String data = multipart ? resources.multipart(req.body(), ct) : new String(req.body(), StandardCharsets.UTF_8);

        if (route.cgi()) {
            if (Files.isDirectory(file.path())) throw new BadRequest();
            resources.verifyCgi(file.path());
            byte[] output = CGIHandler.execute(config, file.path(), data, target.path());
            return responses.bytes(200, "text/plain; charset=utf-8", output);
        }
        if (multipart) {
            return responses.bytes(201, "application/json; charset=utf-8", data.getBytes(StandardCharsets.UTF_8));
        }
        return responses.bytes(200, "text/plain; charset=utf-8", new byte[0]);
    }

    private HttpResponse resourceStatus(ResourceService.Resource resource) {
        if (resource.redirect() != null) return responses.redirect(301, resource.redirect());
        return resource.status() == 200 ? null : responses.error(resource.status());
    }

    private HttpResponse delete(RouteMatcher.Target target, ConfigLoader.Route route) throws IOException {
        ResourceService.Resource file = resources.resolve(target.path(), route, false);
        if (file.redirect() != null) return responses.redirect(301, file.redirect());
        if (file.status() != 200 || !Files.isRegularFile(file.path())) return responses.error(404);
        Files.delete(file.path());
        return responses.text(200, "deleted\n");
    }
}
