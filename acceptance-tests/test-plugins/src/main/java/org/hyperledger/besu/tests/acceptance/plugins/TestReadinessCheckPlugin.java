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

import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.services.HealthCheckService;
import org.hyperledger.besu.plugin.services.PicoCLIOptions;
import org.hyperledger.besu.plugin.services.health.ParamSource;
import org.hyperledger.besu.plugin.services.health.ReadinessCheckProvider;

import com.google.auto.service.AutoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Option;

@AutoService(BesuPlugin.class)
public class TestReadinessCheckPlugin implements BesuPlugin, ReadinessCheckProvider {
  private static final Logger LOG = LoggerFactory.getLogger(TestReadinessCheckPlugin.class);

  private HealthCheckService healthCheckService;

  @Option(
      names = {"--plugin-health-readiness-down"},
      description = "Force readiness to DOWN for testing",
      hidden = true,
      defaultValue = "false")
  boolean readinessDownFlag = false;

  @Override
  public void register(final ServiceManager serviceManager) {
    LOG.info("Registering TestReadinessCheckPlugin");

    // Expose CLI options so ATs can pass flags to control behavior cross-process
    serviceManager
        .getService(PicoCLIOptions.class)
        .ifPresent(pico -> pico.addPicoCLIOptions("health-readiness", this));

    this.healthCheckService =
        serviceManager
            .getService(HealthCheckService.class)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "HealthCheckService is not available - this indicates a serious internal error"));

    // Register the readiness check provider - guaranteed to work since healthCheckService is
    // non-null
    this.healthCheckService.registerReadinessCheckProvider(this);
  }

  @Override
  public void start() {}

  @Override
  public void stop() {
    LOG.info("TestReadinessCheckPlugin stopped");
  }

  @Override
  public boolean isHealthy(final ParamSource paramSource) {
    LOG.info("ParamSource {}", paramSource);
    if (readinessDownFlag) {
      LOG.info("TestReadinessCheckPlugin readiness forced DOWN via CLI flag");
      return false;
    }
    return true;
  }
}
