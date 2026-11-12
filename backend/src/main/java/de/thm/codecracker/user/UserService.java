package de.thm.codecracker.user;

import de.thm.codecracker.user.model.User;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;

import java.util.List;

import org.mindrot.jbcrypt.BCrypt;


public class UserService {
  private static final int MAX_TEXT_LENGTH = 16;
  private static final int MAX_PASSWORD_LENGTH = 26;

  private final UserRepository userRepository;
  private final Vertx vertx;

 
  public UserService(UserRepository userRepository, Vertx vertx) {
    this.userRepository = userRepository;
    this.vertx = vertx;
  }

  public Future<User> register(String username, String rawPassword){
    validateUsername(username);
    validatePassword(rawPassword);
    User existingUser = userRepository.findByUsername(username).await();
    if(existingUser != null){
        throw new IllegalArgumentException("Username already taken");
    }

    String passwordHash = BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    
    return userRepository.create(username, passwordHash, "USER");
  }

  public Future<User> authenticate(String username, String rawPassword) {
  User user = userRepository.findByUsername(username).await();

  if (user == null || !BCrypt.checkpw(rawPassword, user.passwordHash())) {
    throw new IllegalArgumentException("Invalid username or password");
  }
  return Future.succeededFuture(user);
  }



  private void validateUsername(String username){
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("Username must not be empty");
    }
    if (username.length() > MAX_TEXT_LENGTH) {
      throw new IllegalArgumentException(
        "Username must not exceed 16 characters"
      );
    }
  }

  private void validatePassword(String rawPassword){
    if (rawPassword == null || rawPassword.length() <= 6) {
      throw new IllegalArgumentException("Invalid Password");
    }
    if (rawPassword.length() > MAX_PASSWORD_LENGTH) {
      throw new IllegalArgumentException(
        "Password must not exceed 26 characters"
      );
    }

  }

  private void validateId(Long id) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("Invalid user id");
    }
  }

  public Future<User> update(Long id, String username, String rawPassword, String role) {
    validateUsername(username);
    ;

    User existingUser = userRepository.findById(id).await();
    if (existingUser == null) {
      throw new UserNotFoundException();
    }
    
    String passwordHash;
    if (rawPassword == null || rawPassword.isBlank()) {
    passwordHash = existingUser.passwordHash();
    } else {
    validatePassword(rawPassword);
    passwordHash = BCrypt.hashpw(rawPassword, BCrypt.gensalt());
    }

    User updatedUser = userRepository.update(
      id,
      username,
      passwordHash,
      role
    ).await();

    publishUserEvent("updated", JsonObject.mapFrom(updatedUser));

    return Future.succeededFuture(updatedUser);
  }

  private void publishUserEvent(String type, JsonObject payload) {
    var message = new JsonObject()
      .put("type", type)
      .put("payload", payload);

    vertx.eventBus().publish("users", message);
  }

  public Future<List<User>> findAll() {
    List<User> users = userRepository.findAll().await();

    return Future.succeededFuture(users);
  }
  
  public Future<Void> deleteById(Long id) {
    validateId(id);

    boolean deleted = userRepository.deleteById(id).await();

    if (!deleted) {
      throw new UserNotFoundException();
    }

    publishUserEvent("deleted", new JsonObject().put("id", id));

    return Future.succeededFuture();
  }



  




}
