package org.hyperledger.besu.metrics.prometheus;

import io.prometheus.metrics.model.snapshots.Label;
import io.prometheus.metrics.model.snapshots.Labels;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;

import java.util.List;
import java.util.stream.Collectors;

public abstract class CategorizedPrometheusCollector implements PrometheusCollector {
    protected final MetricCategory category;
    protected final String name;
    protected final String prefixedName;

    protected CategorizedPrometheusCollector(final MetricCategory category, final String name) {
        this.category = category;
        this.name = name;
        this.prefixedName = prefixedName(category, name);
    }

    private static String categoryPrefix(final MetricCategory category) {
        return category.getApplicationPrefix().orElse("") + category.getName() + "_";
    }

    /**
     * Get the category prefixed name.
     *
     * @return the name as string
     */
    private static String prefixedName(final MetricCategory category, final String name) {
        return categoryPrefix(category) + name;
    }

    protected static List<String> getLabelValues(final Labels labels) {
        return labels.stream().map(Label::getValue).collect(Collectors.toList());
    }
}
