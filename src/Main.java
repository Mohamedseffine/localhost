import java.nio.file.Path;

/**
 * Entry point for the Java NIO LocalServer.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        try {
            Path configPath = parseConfigPath(args);
            ConfigLoader.ServerConfig config = ConfigLoader.load(configPath);
            Server server = new Server(config);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down server...");
                server.close();
            }));

            server.run();

        } catch (IllegalArgumentException e) {
            System.err.println("Configuration Error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Server Startup Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static Path parseConfigPath(String[] args) {
        if (args == null || args.length == 0) {
            return Path.of("config.json");
        }
        if (args.length == 1) {
            if (args[0].equals("--help") || args[0].equals("-h")) {
                printUsageAndExit();
            }
            if (args[0].startsWith("--config=")) {
                return Path.of(args[0].substring(9));
            }
        }
        if (args.length == 2 && (args[0].equals("--config") || args[0].equals("-c"))) {
            return Path.of(args[1]);
        }
        printUsageAndExit();
        return null;
    }

    private static void printUsageAndExit() {
        System.out.println("LocalServer 2.0 (Java NIO HTTP/1.1 Web Server)");
        System.out.println("Usage: java -jar build/java-server.jar [--config <path_to_config.json>]");
        System.exit(0);
    }
}
