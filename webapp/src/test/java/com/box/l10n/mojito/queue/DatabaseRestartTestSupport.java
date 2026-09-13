package com.box.l10n.mojito.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import org.testcontainers.containers.JdbcDatabaseContainer;

/**
 * Keeps the client endpoint stable while a disposable database container is killed and restarted.
 */
final class DatabaseRestartTestSupport {

  private DatabaseRestartTestSupport() {}

  static int startWithStablePort(JdbcDatabaseContainer<?> database, int containerPort)
      throws IOException {
    int hostPort;
    try (ServerSocket availablePort = new ServerSocket(0)) {
      hostPort = availablePort.getLocalPort();
      // Docker may reallocate an unspecified host port after a hard stop/start. The existing
      // application pools must reconnect without changing their original JDBC URLs.
      database.setPortBindings(List.of(hostPort + ":" + containerPort));
    }
    database.start();
    assertStablePort(database, containerPort, hostPort);
    return hostPort;
  }

  static void assertStablePort(
      JdbcDatabaseContainer<?> database, int containerPort, int expectedHostPort) {
    // GenericContainer caches its initial inspection. Read Docker again after every start.
    Ports.Binding[] bindings =
        database
            .getDockerClient()
            .inspectContainerCmd(database.getContainerId())
            .exec()
            .getNetworkSettings()
            .getPorts()
            .getBindings()
            .get(ExposedPort.tcp(containerPort));
    assertThat(bindings)
        .as("live Docker bindings for database port %s", containerPort)
        .isNotEmpty()
        .allSatisfy(
            binding ->
                assertThat(binding.getHostPortSpec())
                    .isEqualTo(Integer.toString(expectedHostPort)));
  }
}
