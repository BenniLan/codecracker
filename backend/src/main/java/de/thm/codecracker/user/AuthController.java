package de.thm.codecracker.user;

import de.thm.codecracker.user.model.User;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles HTTP requests for registration and login.
 *
 * <p>Converts requests into {@link UserService} calls, and on successful
 * login, issues a signed JWT containing the user's identity and role.</p>
 */
public class AuthController {
  private static final Logger LOGGER = LoggerFactory.getLogger(AuthController.class);

  private final UserService userService;
  private final JWTAuth jwtAuth;

  public AuthController(UserService userService, JWTAuth jwtAuth) {
    this.userService = userService;
    this.jwtAuth = jwtAuth;
  }

  /**
   * Registers the public auth routes on the given router.
   *
   * @param router the Vert.x router used to register the routes
   */
  public void registerRoutes(Router router) {
    router.post("/api/register").handler(this::register);
    router.post("/api/login").handler(this::login);
  }

  private void register(RoutingContext ctx) {
    try {
      JsonObject request = ctx.body().asJsonObject();
      User user = userService.register(
        request.getString("username"),
        request.getString("password")
      ).await();

      ctx.response()
        .setStatusCode(201)
        .putHeader("content-type", "application/json")
        .end(new JsonObject()
          .put("username", user.username())
          .put("role", user.role())
          .encode());
    } catch (IllegalArgumentException exception) {
      sendError(ctx, 400, exception.getMessage());
    } catch (Exception exception) {
      sendUnexpectedError(ctx, exception);
    }
  }

  private void login(RoutingContext ctx) {
    try {
      JsonObject request = ctx.body().asJsonObject();
      User user = userService.authenticate(
        request.getString("username"),
        request.getString("password")
      ).await();

      String token = jwtAuth.generateToken(
        new JsonObject()
          .put("sub", user.username())
          .put("role", user.role())
      );

      ctx.response()
        .putHeader("content-type", "application/json")
        .end(new JsonObject().put("token", token).encode());
    } catch (IllegalArgumentException exception) {
      sendError(ctx, 401, exception.getMessage());
    } catch (Exception exception) {
      sendUnexpectedError(ctx, exception);
    }
  }

  private void sendError(RoutingContext ctx, int statusCode, String message) {
    ctx.response()
      .setStatusCode(statusCode)
      .putHeader("content-type", "application/json")
      .end(new JsonObject()
        .put("error", message)
        .encode());
  }

  private void sendUnexpectedError(RoutingContext ctx, Exception exception) {
    LOGGER.error(
      "Unexpected error while handling {} {}",
      ctx.request().method(),
      ctx.request().path(),
      exception
    );
    sendError(ctx, 500, "Unexpected server error");
  }
}