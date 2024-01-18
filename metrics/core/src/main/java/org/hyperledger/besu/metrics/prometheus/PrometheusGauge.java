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

import com.google.common.base.Preconditions;
import io.prometheus.metrics.core.datapoints.GaugeDataPoint;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import org.hyperledger.besu.metrics.Observation;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledGauge;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.stream.Stream;

/** The Prometheus gauge. */
public class PrometheusGauge extends CategorizedPrometheusCollector implements LabelledGauge {
  private final Gauge gauge;
  private final Map<GaugeDataPoint, DoubleSupplier> observationsMap = new ConcurrentHashMap<>();

  /**
   * Instantiates a new Prometheus gauge.
   *
   * @param name the metric name
   * @param help the help
   * @param labelNames the label names
   */
  public PrometheusGauge(final MetricCategory category,
      final String name, final String help, final String... labelNames) {
    super(category, name);
    this.gauge = Gauge.builder().name(this.prefixedName).help(help).labelNames(labelNames).build();
  }

  public PrometheusGauge(final MetricCategory category,
          final String metricName, final String help, final DoubleSupplier valueSupplier) {
    super(category, metricName);
    this.gauge = Gauge.builder().name(metricName).help(help).build();
    observationsMap.put(gauge, valueSupplier);
  }

  @Override
  public synchronized void labels(final DoubleSupplier valueSupplier, final String... labelValues) {
    final var gaugeDataPoint = gauge.labelValues(labelValues);
    if (observationsMap.putIfAbsent(gaugeDataPoint, valueSupplier) != null) {
      final String labelValuesString = String.join(",", labelValues);
      throw new IllegalArgumentException(
          String.format("A gauge has already been created for label values %s", labelValuesString));
    }
  }

  @Override
  public String getName() {
    return gauge.getPrometheusName();
  }

  @Override
  public Collector toCollector() {
    return gauge;
  }

  private Observation convertToObservation(final GaugeSnapshot.GaugeDataPointSnapshot sample) {
    final List<String> labelValues = getLabelValues(sample.getLabels());

    return new Observation(
            category,
            name,
            sample.getValue(),
            labelValues);
  }

  @Override
  public Stream<Observation> streamObservations() {
    final var snapshot = gauge.collect();
    return snapshot.getDataPoints().stream().map(this::convertToObservation);
  }

}
