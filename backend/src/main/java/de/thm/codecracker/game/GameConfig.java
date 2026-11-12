package de.thm.codecracker.game;

import java.util.List;

/**
 * Zentrale, unveränderliche Konfigurationswerte für die Codecracker-Spiellogik.
 *
 * <p>Fasst alle Basiskonfigurationswerte (Farben, Codelänge, Rundenlimit,
 * Rundendauer, Punktevergabe) an einer Stelle zusammen, damit {@link GameService}
 * und die zugehörigen Repositories/Controller nicht mit verstreuten
 * Magic-Numbers arbeiten müssen.</p>
 */
public final class GameConfig {
  public static final List<String> COLORS = List.of("RED", "GREEN", "BLUE", "YELLOW");
  public static final int CODE_LENGTH = 4;
  public static final int MAX_ROUNDS = 10;
  public static final long ROUND_DURATION_MS = 60_000;
  public static final int WINNER_POINTS = 100;

  private GameConfig() {
  }
}