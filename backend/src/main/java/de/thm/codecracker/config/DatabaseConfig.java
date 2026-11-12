package de.thm.codecracker.config;

import io.vertx.core.Vertx;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;

public class DatabaseConfig {
  private DatabaseConfig() {
  }

  public static Pool createPool(Vertx vertx, AppConfig config) {
    MySQLConnectOptions connectOptions = new MySQLConnectOptions()
      .setHost(config.dbHost())
      .setPort(config.dbPort())
      .setDatabase(config.dbName())
      .setUser(config.dbUser())
      .setPassword(config.dbPassword());

    PoolOptions poolOptions = new PoolOptions()
      .setMaxSize(10);

    return Pool.pool(vertx, connectOptions, poolOptions);
  }
}
