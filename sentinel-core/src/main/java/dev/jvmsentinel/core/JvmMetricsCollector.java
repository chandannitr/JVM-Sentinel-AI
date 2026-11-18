package dev.jvmsentinel.core;

@FunctionalInterface
public interface JvmMetricsCollector {
    JvmSnapshot collect();
}
