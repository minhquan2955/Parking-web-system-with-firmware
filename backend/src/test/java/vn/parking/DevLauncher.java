package vn.parking;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.File;

/** Local-only launcher with a real, persistent PostgreSQL instance. No Docker required. */
public class DevLauncher {
  public static void main(String[] args) throws Exception {
    File directory =
        new File(System.getProperty("parking.local-data", ".runtime/postgres")).getAbsoluteFile();
    var postgres =
        EmbeddedPostgres.builder()
            .setPort(5440)
            .setDataDirectory(directory)
            .setCleanDataDirectory(false)
            .start();
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    postgres.close();
                  } catch (Exception ignored) {
                  }
                }));
    System.setProperty("spring.datasource.url", postgres.getJdbcUrl("postgres", "postgres"));
    System.setProperty("spring.datasource.username", "postgres");
    System.setProperty("spring.datasource.password", "");
    System.setProperty("server.address", "127.0.0.1");
    System.setProperty("spring.profiles.active", "dev");
    ParkingApplication.main(args);
  }
}
