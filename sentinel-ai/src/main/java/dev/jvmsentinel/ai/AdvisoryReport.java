package dev.jvmsentinel.ai;

import java.util.List;

public record AdvisoryReport(EvidenceBundle.Rule incident, String summary, String likelyCause,
                             Confidence confidence, List<String> evidenceIds,
                             List<String> alternativeHypotheses, List<String> recommendations,
                             List<String> limitations) {
    public AdvisoryReport {
        evidenceIds = List.copyOf(evidenceIds);
        alternativeHypotheses = List.copyOf(alternativeHypotheses);
        recommendations = List.copyOf(recommendations);
        limitations = List.copyOf(limitations);
    }

    public enum Confidence { low, medium, high, unknown }
}
