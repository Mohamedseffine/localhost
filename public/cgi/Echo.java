final class Echo {
    public static void main(String[] args) {
        System.out.println("PATH_INFO=" + System.getenv().getOrDefault("PATH_INFO", ""));
        System.out.println("DATA=" + (args.length > 0 ? args[0] : ""));
    }
}
