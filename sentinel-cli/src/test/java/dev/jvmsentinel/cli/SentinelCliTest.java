package dev.jvmsentinel.cli;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SentinelCliTest {
    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> SentinelCli.main(new String[] {"--iterations=0"}));
    }
}
