/*
 * Copyright contributors to Besu.
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
package org.hyperledger.besu.metrics.prometheus;

import static org.hyperledger.besu.metrics.prometheus.PrometheusCollector.addLabelValues;
import static org.hyperledger.besu.metrics.prometheus.PrometheusCollector.getLabelValues;

import org.hyperledger.besu.metrics.Observation;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;

import java.util.ArrayList;
import java.util.stream.Stream;

import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.SummarySnapshot;

abstract class AbstractPrometheusSummary extends CategorizedPrometheusCollector {
  /** The collector */
  protected final Collector collector;

  /**
   * Create a new collector assigned to the given category and with the given name, and computed the
   * prefixed name.
   *
   * @param category The {@link MetricCategory} this collector is assigned to
   * @param name The name of this collector
   */
  protected AbstractPrometheusSummary(
      final MetricCategory category,
      final String name,
      final String help,
      final String... labelNames) {
    super(category, name);
    this.collector = createCollector(help, labelNames);
  }

  /**
   * Create the actual collector
   *
   * @param help the help
   * @param labelNames the label names
   * @return the created collector
   */
  protected abstract Collector createCollector(final String help, final String... labelNames);

  @Override
  public String getIdentifier() {
    return collector.getPrometheusName();
  }

  @Override
  public void register(final PrometheusRegistry registry) {
    registry.register(collector);
  }

  @Override
  public void unregister(final PrometheusRegistry registry) {
    registry.unregister(collector);
  }

  private SummarySnapshot collect() {
    return (SummarySnapshot) collector.collect();
  }

  @Override
  public Stream<Observation> streamObservations() {
    return collect().getDataPoints().stream()
        .flatMap(
            dataPoint -> {
              final var labelValues = getLabelValues(dataPoint.getLabels());
              final var quantiles = dataPoint.getQuantiles();
              final var observations = new ArrayList<Observation>(quantiles.size() + 2);

              if (dataPoint.hasSum()) {
                observations.add(
                    new Observation(
                        category, name, dataPoint.getSum(), addLabelValues(labelValues, "sum")));
              }

              if (dataPoint.hasCount()) {
                observations.add(
                    new Observation(
                        category,
                        name,
                        dataPoint.getCount(),
                        addLabelValues(labelValues, "count")));
              }

              quantiles.forEach(
                  quantile ->
                      observations.add(
                          new Observation(
                              category,
                              name,
                              quantile.getValue(),
                              addLabelValues(
                                  labelValues,
                                  "quantile",
                                  Double.toString(quantile.getQuantile())))));

              return observations.stream();
            });
  }
}
