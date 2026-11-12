package de.thm.codecracker.user;

import de.thm.codecracker.user.model.User;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class UserWebSocketController {
  private static final Logger LOGGER = LoggerFactory.getLogger(UserWebSocketController.class);
  private static final String USER_PATH = "/ws/users";
  private static final String USER_EVENT_ADDRESS = "users";

  private final Vertx vertx;
  private final UserService userService;
  private final JWTAuth jwtAuth;
  private final Set<ServerWebSocket> sockets = ConcurrentHashMap.newKeySet();

  public UserWebSocketController(Vertx vertx, UserService userService, JWTAuth jwtAuth) {
    this.vertx = vertx;
    this.userService = userService;
    this.jwtAuth = jwtAuth;
  }
  private JsonObject toPublicJson(de.thm.codecracker.user.model.User user) {
  return new JsonObject()
    .put("id", user.id())
    .put("username", user.username())
    .put("role", user.role());
}

  public void registerRoutes(Router router) {
    router.get(USER_PATH).handler(this::upgradeConnection);
  }

  public void registerEventBusConsumer() {
    vertx.eventBus().consumer(USER_EVENT_ADDRESS, message ->
      broadcast((JsonObject) message.body())
    );
  }

  private void upgradeConnection(RoutingContext context) {
    String token = context.request().getParam("token");

    if (token == null || token.isBlank()) {
      context.response().setStatusCode(401).end();
      return;
    }

    jwtAuth.authenticate(new TokenCredentials(token))
      .onSuccess(user -> {
        String role = user.principal().getString("role");

        if (!"ADMIN".equals(role)) {
          context.response().setStatusCode(403).end();
          return;
        }

        try {
          ServerWebSocket socket = context.request().toWebSocket().await();
          handleConnection(socket);
        } catch (Exception exception) {
          context.fail(exception);
        }
      })
      .onFailure(err -> context.response().setStatusCode(401).end());
  }

  private void handleConnection(ServerWebSocket socket) {
    sockets.add(socket);
    socket.textMessageHandler(message -> handleUserCommand(socket, message));
    socket.closeHandler(unused -> sockets.remove(socket));
    socket.exceptionHandler(error -> sockets.remove(socket));
  }

  private void handleUserCommand(ServerWebSocket socket, String message) {
    String requestId = null;

    try {
      JsonObject command = new JsonObject(message);
      requestId = requiredString(command, "requestId");
      String type = requiredString(command, "type");
      JsonObject payload = command.getJsonObject("payload", new JsonObject());

      Object result = switch (type) {
        case "find-all" -> findAllUsers();
        case "create" -> createUser(payload);
        case "update" -> updateUser(payload);
        case "delete" -> deleteUser(payload);
        default -> throw new IllegalArgumentException("Unknown command: " + type);
      };

      sendResponse(socket, requestId, result);
    } catch (UserNotFoundException | IllegalArgumentException exception) {
      sendError(socket, requestId, exception.getMessage());
    } catch (Exception exception) {
      LOGGER.error("Unexpected error while handling User WebSocket command", exception);
      sendError(socket, requestId, "Unexpected server error");
    }
  }

 private JsonArray findAllUsers() {
  return new JsonArray(userService.findAll().await().stream()
    .map(this::toPublicJson)
    .toList());
}

private JsonObject createUser(JsonObject payload) {
  var user = userService.register(payload.getString("username"), payload.getString("password")).await();
  return toPublicJson(user);
}

 private JsonObject updateUser(JsonObject payload) {
  var user = userService.update(
    requiredId(payload),
    payload.getString("username"),
    payload.getString("password"),
    payload.getString("role")
  ).await();
  return toPublicJson(user);
}
  private JsonObject deleteUser(JsonObject payload) {
    long id = requiredId(payload);
    userService.deleteById(id).await();
    return new JsonObject().put("id", id);
  }

  private String requiredString(JsonObject object, String fieldName) {
    String value = object.getString(fieldName);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private long requiredId(JsonObject payload) {
    Number id = payload.getNumber("id");
    if (id == null) {
      throw new IllegalArgumentException("id is required");
    }
    return id.longValue();
  }

  private void sendResponse(ServerWebSocket socket, String requestId, Object payload) {
    socket.writeTextMessage(new JsonObject()
      .put("type", "response")
      .put("requestId", requestId)
      .put("payload", payload)
      .encode());
  }

  private void sendError(ServerWebSocket socket, String requestId, String message) {
    socket.writeTextMessage(new JsonObject()
      .put("type", "response")
      .put("requestId", requestId)
      .put("error", message == null ? "Command failed" : message)
      .encode());
  }

  private void broadcast(JsonObject message) {
    for (var socket : sockets) {
      if (!socket.isClosed()) {
        socket.writeTextMessage(message.encode());
      }
    }
  }

}