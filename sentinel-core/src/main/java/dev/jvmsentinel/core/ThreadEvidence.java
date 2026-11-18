package dev.jvmsentinel.core;

import java.util.List;
import java.util.Objects;

/** A bounded platform-thread observation. Lock fields may be absent. */
public record ThreadEvidence(long threadId, String name, Thread.State state,
                             String lockName, long lockOwnerId, String lockOwnerName,
                             List<StackTraceElement> stackTrace) {
    public ThreadEvidence {
        Objects.requireNonNull(name);
        Objects.requireNonNull(state);
        stackTrace = List.copyOf(stackTrace);
    }
}
