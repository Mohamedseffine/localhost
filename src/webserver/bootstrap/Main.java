package webserver.bootstrap;

import java.nio.file.Path;
import webserver.config.ConfigLoader;
import webserver.transport.Server;

/** Command-line entry point for the HTTP service. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            Path configPath = configuration(args);

            ConfigLoader.Config config = ConfigLoader.load(configPath);
            Server server = new Server(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    server.close();
                } catch (Exception ignored) {}
            }));

            try {
                server.run();
            } finally {
                server.close();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Config error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Path configuration(String[] args) {
        if (args.length == 0) return Path.of("config.json");
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("Usage: java -jar build/java-server.jar [--config config.json]");
            System.exit(0);
        }
        if (args.length == 1 && args[0].startsWith("--config=")) return Path.of(args[0].substring(9));
        if (args.length == 2 && (args[0].equals("--config") || args[0].equals("-c"))) return Path.of(args[1]);
        throw new IllegalArgumentException("Use --config <file>");
    }
}
