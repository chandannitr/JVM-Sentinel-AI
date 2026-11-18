package dev.jvmsentinel.cli;

import dev.jvmsentinel.ai.EvidenceBundle;
import dev.jvmsentinel.ai.OllamaIncidentExplainer;

final class AdvisoryOutput {
    private AdvisoryOutput() { }

    static OllamaIncidentExplainer.Result local(EvidenceBundle bundle) throws InterruptedException {
        String model = System.getenv().getOrDefault("OLLAMA_MODEL", "llama3.2:3b");
        return new OllamaIncidentExplainer(model).explain(bundle);
    }

    static OllamaIncidentExplainer.Result print(EvidenceBundle bundle, Analysis analysis) throws InterruptedException {
        if (bundle.items().isEmpty()) {
            System.out.println("No supported observations. AI analysis was skipped.");
            return new OllamaIncidentExplainer.Result(OllamaIncidentExplainer.Status.SKIPPED, null);
        }
        System.out.println("Observed facts");
        for (var item : bundle.items()) {
            System.out.printf("  %s = %d %s%n", item.metric().id(), item.value(), item.metric().unit());
        }
        for (var metric : bundle.notCollected()) {
            System.out.printf("  %s = not_collected%n", metric.id());
        }
        System.out.println("Deterministic detection");
        if (bundle.detections().isEmpty()) {
            System.out.println("  No rule incident. Workload observations only.");
        }
        for (var detection : bundle.detections()) {
            System.out.printf("  [%s] %s at %s%n", detection.severity(), detection.rule(), detection.timestamp());
            System.out.println("  Rule: " + detection.rule().predicate());
        }

        var result = analysis.explain(bundle);
        var report = result.report();
        if (report == null) {
            System.out.println("AI advisory unavailable. Showing deterministic report.");
            System.out.println("  " + result.status().message());
            return result;
        }
        System.out.println("AI hypothesis");
        System.out.println("  Classification: " + (report.incident() == null ? "observations only" : report.incident()));
        System.out.println("  " + report.summary());
        System.out.println("  Possible explanation: " + report.likelyCause());
        report.alternativeHypotheses().forEach(text -> System.out.println("  Alternative: " + text));
        System.out.println("Evidence references");
        report.evidenceIds().forEach(id -> System.out.println("  " + id));
        System.out.println("Suggested next diagnostic steps");
        report.recommendations().forEach(text -> System.out.println("  " + text));
        System.out.println("Uncertainty/limitations");
        System.out.println("  Model confidence: " + report.confidence() + " (qualitative)");
        report.limitations().forEach(text -> System.out.println("  " + text));
        return result;
    }

    @FunctionalInterface
    interface Analysis {
        OllamaIncidentExplainer.Result explain(EvidenceBundle bundle) throws InterruptedException;
    }
}
