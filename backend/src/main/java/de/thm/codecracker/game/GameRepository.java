package de.thm.codecracker.game;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import java.util.List;

/**
 * Kapselt sämtliche Datenbankzugriffe für die Tabellen {@code games} und
 * {@code game_players}.
 */

public class GameRepository {
  private final Pool pool;

  public GameRepository(Pool pool) {
    this.pool = pool;
  }

  /**
   * Legt einen neuen Datensatz in der Tabelle {@code games} an, mit Status
   * {@code IN_PROGRESS}.
   *
   * @param secretCode der generierte Geheimcode, als kommagetrennte Farbliste
   * @param roundLimit die maximale Rundenanzahl für dieses Spiel
   * @return ein {@link Future}, das nach erfolgreichem Insert die generierte
   *         Datenbank-ID des neuen Spiels liefert
   */
  public Future<Long> create(String secretCode, int roundLimit) {
    String sql = """
      INSERT INTO games (status, secret_code, round_limit)
      VALUES ('IN_PROGRESS', ?, ?)
      """;

    return pool.preparedQuery(sql)
      .execute(Tuple.of(secretCode, roundLimit))
      .map(rows -> rows.property(io.vertx.mysqlclient.MySQLClient.LAST_INSERTED_ID));
  }

  /**
   * Markiert ein Spiel als beendet: setzt den übergebenen Status sowie
   * {@code ended_at} auf den aktuellen Zeitpunkt.
   *
   * @param gameId die ID des zu aktualisierenden Spiels
   * @param status der Endstatus, z. B. {@code FINISHED} oder {@code FINISHED_NO_WINNER}
   */
  public Future<Void> markFinished(long gameId, String status) {
    String sql = """
      UPDATE games SET status = ?, ended_at = NOW() WHERE id = ?
      """;

    return pool.preparedQuery(sql)
      .execute(Tuple.of(status, gameId))
      .mapEmpty();
  }

  /**
   * Speichert das Endergebnis eines einzelnen Spielers für ein Spiel in
   * {@code game_players}.
   *
   * @param gameId die ID des zugehörigen Spiels
   * @param userId die ID des Spielers
   * @param score  die erzielte Punktzahl (3 für Sieg, 1 für Unentschieden, -1 für Niederlage)
   */
  public Future<Void> addPlayerScore(long gameId, long userId, int score) {
    String sql = """
      INSERT INTO game_players (game_id, user_id, score) VALUES (?, ?, ?)
      """;

    return pool.preparedQuery(sql)
      .execute(Tuple.of(gameId, userId, score))
      .mapEmpty();
  }

}
 
