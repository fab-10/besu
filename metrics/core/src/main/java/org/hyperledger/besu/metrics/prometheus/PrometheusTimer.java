/*
 * Copyright ConsenSys AG.
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

import io.prometheus.metrics.core.datapoints.DistributionDataPoint;
import io.prometheus.metrics.core.metrics.Summary;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.Label;
import io.prometheus.metrics.model.snapshots.Quantile;
import io.prometheus.metrics.model.snapshots.SummarySnapshot;
import org.hyperledger.besu.metrics.Observation;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;
import org.hyperledger.besu.plugin.services.metrics.OperationTimer;

import io.prometheus.client.Summary;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

class PrometheusTimer extends CategorizedPrometheusCollector implements LabelledMetric<OperationTimer> {

  private final Summary summary;

  public PrometheusTimer(final MetricCategory category, final String name,
                         final String help,
                         final String... labelNames) {
    super(category, name);
    this.summary =                     Summary.builder()
            .name(this.prefixedName)
            .help(help)
            .labelNames(labelNames)
            .quantile(0.2, 0.02)
            .quantile(0.5, 0.05)
            .quantile(0.8, 0.02)
            .quantile(0.95, 0.005)
            .quantile(0.99, 0.001)
            .quantile(1.0, 0)
            .build();
  }

  @Override
  public OperationTimer labels(final String... labels) {
    final DistributionDataPoint metric = summary.labelValues(labels);
    return () -> metric.startTimer()::observeDuration;
  }

  @Override
  public String getName() {
    return summary.getPrometheusName();
  }

  @Override
  public Collector toCollector() {
    return summary;
  }

  @Override
  public Stream<Observation> streamObservations() {
    final var snapshot = summary.collect();
    return snapshot.getDataPoints().stream()
            .flatMap(dataPoint -> {
              final var labels = getLabelValues(dataPoint.getLabels());
              return StreamSupport.stream(dataPoint.getQuantiles().spliterator(), false)
                      .map(q -> convertToObservation(labels, q));
            });
  }

  private Observation convertToObservation(final List<String> labelValues, final Quantile sample) {
      labelValues.add("quantile");
              labelValues.add(Double.toString(sample.getQuantile()));
    return new Observation(
            category,
            name,
            sample.getValue(),
            labelValues);
  }
}
