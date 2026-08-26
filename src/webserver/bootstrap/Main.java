package webserver.bootstrap;

import java.nio.file.Path;
import webserver.config.ConfigLoader;
import webserver.transport.Server;

/** Server bootstrap and CLI runner. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            Path configPath = args.length == 0 ? Path.of("config.json")
                    : (args.length == 2 && (args[0].equals("--config") || args[0].equals("-c"))) ? Path.of(args[1])
                    : (args.length == 1 && args[0].startsWith("--config=")) ? Path.of(args[0].substring(9))
                    : null;

            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
                System.out.println("Usage: java -jar build/java-server.jar [--config config.json]");
                System.exit(0);
            }

            if (configPath == null) {
                throw new IllegalArgumentException("Use --config <file>");
            }

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
}
