package dev.jvmsentinel.history;

import dev.jvmsentinel.ai.EvidenceBundle;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentHistoryRepository {
    StoredReport save(EvidenceBundle evidence, StoredReport.Origin origin, StoredReport.Advisory advisory)
            throws IOException;
    Optional<StoredReport> findById(UUID id) throws IOException;
    QueryResult query() throws IOException;
    int enforceRetention() throws IOException;

    record QueryResult(List<StoredReport> reports, int corrupt, int unsupported, int oversized, boolean truncated) {
        public QueryResult { reports = List.copyOf(reports); }
    }
}
