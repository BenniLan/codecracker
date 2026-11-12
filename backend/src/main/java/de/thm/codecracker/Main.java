package de.thm.codecracker;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import io.vertx.core.Vertx;

/**
 * Starts the application and deploys the main Vert.x verticle.
 *
 * <p>The application configures Vert.x to run the main verticle using
 * virtual threads.</p>
 */
public class Main {

  /**
   * Starts the application.
   *
   * <p>The method creates the Vert.x instance, and deploys the {@link MainVerticle} with the
   * {@link ThreadingModel#VIRTUAL_THREAD} threading model.</p>
   *
   * @param args the command-line arguments
   */
  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();

    DeploymentOptions options = new DeploymentOptions()
      .setThreadingModel(ThreadingModel.VIRTUAL_THREAD);

    vertx.deployVerticle(MainVerticle::new, options).await();
  }
}
