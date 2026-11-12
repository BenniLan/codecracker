package de.thm.codecracker.config;

public record AppConfig(
  String dbHost,
  int dbPort,
  String dbName,
  String dbUser,
  String dbPassword,
  int httpPort
) {
  public static AppConfig fromEnvironment() {
    return new AppConfig(
      env("DB_HOST", "127.0.0.1"),
      envInt("DB_PORT", 3306),
      env("DB_NAME", "codecracker"),
      env("DB_USER", "codecracker"),
      env("DB_PASSWORD", "secret"),
      envInt("HTTP_PORT", 8080)
    );
  }

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static int envInt(String name, int defaultValue) {
    String value = System.getenv(name);

    if (value == null || value.isBlank()) {
      return defaultValue;
    }

    return Integer.parseInt(value);
  }
}
