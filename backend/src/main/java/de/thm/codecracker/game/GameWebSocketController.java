package de.thm.codecracker.game;

import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.vertx.core.json.JsonArray;
import java.util.List;


// WebSocket-Endpunkt für das eigentliche Spielfeld
public class GameWebSocketController {
  private static final Logger LOGGER = LoggerFactory.getLogger(GameWebSocketController.class);
  private static final String GAME_PATH = "/ws/game";

  private final JWTAuth jwtAuth;
  private final GameService gameService;

  public GameWebSocketController(JWTAuth jwtAuth, GameService gameService) {
    this.jwtAuth = jwtAuth;
    this.gameService = gameService;
  }

  public void registerRoutes(Router router) {
    router.get(GAME_PATH).handler(this::upgradeConnection);
  }

  /**
   * Behandelt den initialen HTTP-Request für den WebSocket-Upgrade: prüft
   * das JWT im Query-Parameter {@code token} sowie, ob der Nutzer aktuell am
   * Spiel teilnimmt, und führt bei Erfolg das WebSocket-Upgrade durch.
   */
  private void upgradeConnection(RoutingContext context) {
    String token = context.request().getParam("token");

    if (token == null) {
      context.response().setStatusCode(401).end("Missing token");
      return;
    }

    try {
      var jwtUser = jwtAuth.authenticate(new TokenCredentials(token)).await();
      String username = jwtUser.principal().getString("sub");

      if (!gameService.isParticipant(username)) {
        context.response().setStatusCode(403).end("Not part of an active game");
        return;
      }

      ServerWebSocket socket = context.request().toWebSocket().await();
      handleConnection(socket, username);
    } catch (Exception exception) {
      LOGGER.error("Game WebSocket auth failed", exception);
      context.response().setStatusCode(401).end("Invalid token");
    }
  }

  /**
   * Registriert einen erfolgreich verbundenen Socket beim {@link GameService}
   * und richtet die Message- sowie Close-Handler ein.
   *
   * @param socket   der neu verbundene WebSocket
   * @param username der Benutzername des verbundenen Spielers
   */
  private void handleConnection(ServerWebSocket socket, String username) {
    gameService.registerSocket(username, socket);

    socket.textMessageHandler(message -> handleCommand(socket, username, message));
    socket.closeHandler(unused -> gameService.unregisterSocket(username, socket));
  }

  // Verarbeitet ein einzelnes eingehendes Text-Kommando.
  @SuppressWarnings("unchecked")
  private void handleCommand(ServerWebSocket socket, String username, String message) {
    try {
      JsonObject command = new JsonObject(message);
      String type = command.getString("type");
      JsonObject payload = command.getJsonObject("payload", new JsonObject());

      switch (type) {
        case "submit-guess" -> {
          List<String> colors = (List<String>) (List<?>) payload.getJsonArray("colors", new JsonArray()).getList();
          gameService.submitGuess(username, colors);
        }
        case "abort-game" -> gameService.forceEnd();
        default -> LOGGER.warn("Unknown game command: {}", type);
      }
    } catch (IllegalArgumentException exception) {
      sendError(socket, exception.getMessage());
    } catch (Exception exception) {
      LOGGER.error("Unexpected error while handling game command", exception);
      sendError(socket, "Unexpected server error");
    }
  }

  // Sendet eine Fehlermeldung an einen Client.
  private void sendError(ServerWebSocket socket, String message) {
    socket.writeTextMessage(new JsonObject()
      .put("type", "error")
      .put("payload", new JsonObject().put("message", message))
      .encode());
  }


}
