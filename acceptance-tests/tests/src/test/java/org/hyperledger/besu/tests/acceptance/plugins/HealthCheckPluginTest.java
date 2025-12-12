/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.tests.acceptance.plugins;

import static org.assertj.core.api.Assertions.assertThat;

import org.hyperledger.besu.tests.acceptance.dsl.AcceptanceTestBase;
import org.hyperledger.besu.tests.acceptance.dsl.node.BesuNode;

import java.io.IOException;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class HealthCheckPluginTest extends AcceptanceTestBase {

  private BesuNode node;
  private OkHttpClient client;

  @BeforeEach
  public void setUp() throws Exception {
    client = new OkHttpClient();
  }

  @Test
  public void livenessEndpointShouldReturn200WhenHealthy() throws IOException {
    node = besu.createPluginsNode("node1", List.of("testPlugins"), List.of());
    cluster.start(node);

    // liveness endpoint
    Response response = callHealthEndpoint("/liveness");
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).contains("UP");
  }

  @Test
  public void livenessEndpointShouldReturn503WhenForcedDownViaCli() throws IOException {
    node =
        besu.createPluginsNode(
            "node-down", List.of("testPlugins"), List.of("--plugin-health-liveness-down=true"));
    cluster.start(node);

    Response response = callHealthEndpoint("/liveness");
    assertThat(response.code()).isEqualTo(503);
  }

  @Test
  public void readinessEndpointShouldReturn200WhenHealthy() throws IOException {
    node = besu.createPluginsNode("node1", List.of("testPlugins"), List.of());
    final var node2 = besu.createPluginsNode("node2", List.of("testPlugins"), List.of());
    cluster.start(node2, node);

    node.awaitPeerDiscovery(net.awaitPeerCount(1));

    // readiness endpoint
    Response response = callHealthEndpoint("/readiness");
    assertThat(response.code()).isEqualTo(200);
    assertThat(response.body().string()).contains("UP");
  }

  @Test
  public void readinessEndpointShouldRespectMinPeersParameter() throws IOException {
    node = besu.createPluginsNode("node1", List.of("testPlugins"), List.of());
    cluster.start(node);

    // different minPeers parameters
    Response response1 = callHealthEndpoint("/readiness?minPeers=0");
    assertThat(response1.code()).isEqualTo(200);

    Response response2 = callHealthEndpoint("/readiness?minPeers=100");
    // if we have less than 100 peers
    assertThat(response2.code()).isEqualTo(503);
  }

  //  @Test
  //  public void readinessEndpointShouldRespectMaxBlocksBehindParameter() throws IOException {
  //    final var node2 =
  //        besu.createQbftPluginsNode(
  //            "node2",
  //            Collections.singletonList("testPlugins"),
  //            List.of());
  //    final var node3 =
  //        besu.createQbftPluginsNode(
  //            "node3",
  //            Collections.singletonList("testPlugins"),
  //            List.of());
  //    final var node4 =
  //        besu.createQbftPluginsNode(
  //            "node4",
  //            Collections.singletonList("testPlugins"),
  //            List.of());
  //    node = besu.createQbftPluginsNode("node1", List.of("testPlugins"), List.of());
  //
  //    cluster.start(node2,node3, node4, node);
  //
  //    node.awaitPeerDiscovery(net.awaitPeerCount(3));
  //    node.verify(eth.syncingStatus(false));
  //
  //    Awaitility.await().until(() -> {
  //    Response response2 = callHealthEndpoint("/readiness?maxBlocksBehind=0");
  //    // if we're behind by any blocks, it should fail
  //      return response2.code() == 503;});
  ////    assertThat(response2.code()).isEqualTo(503);
  //
  ////    // different maxBlocksBehind parameters
  ////    Response response1 = callHealthEndpoint("/readiness?maxBlocksBehind=1000");
  ////    assertThat(response1.code()).isEqualTo(200);
  //  }

  @Test
  public void readinessEndpointShouldReturn503WhenForcedDownViaCli() throws IOException {
    node =
        besu.createPluginsNode(
            "node-down", List.of("testPlugins"), List.of("--plugin-health-readiness-down=true"));
    cluster.start(node);

    Response response = callHealthEndpoint("/readiness");
    assertThat(response.code()).isEqualTo(503);
  }

  @Test
  public void healthEndpointsShouldFailOnInvalidParameters() throws IOException {
    node = besu.createPluginsNode("node-down", List.of("testPlugins"), List.of());
    cluster.start(node);

    // invalid parameters
    Response response1 = callHealthEndpoint("/readiness?minPeers=invalid");
    // default value
    assertThat(response1.code()).isEqualTo(503);

    Response response2 = callHealthEndpoint("/readiness?maxBlocksBehind=invalid");
    assertThat(response2.code()).isEqualTo(503);
  }

  private Response callHealthEndpoint(final String path) throws IOException {
    String url = "http://" + node.getHostName() + ":" + node.getJsonRpcPort().get() + path;
    Request request = new Request.Builder().url(url).build();
    return client.newCall(request).execute();
  }
}
