package org.hyperledger.besu.metrics.prometheus;

import io.prometheus.metrics.model.registry.Collector;
import org.hyperledger.besu.metrics.Observation;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;

import java.util.stream.Stream;

public interface PrometheusCollector {
    String getName();

    Collector toCollector();

    Stream<Observation> streamObservations();

}
