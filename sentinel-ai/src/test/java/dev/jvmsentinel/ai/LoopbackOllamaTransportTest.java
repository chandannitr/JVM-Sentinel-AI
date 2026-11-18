package dev.jvmsentinel.ai;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class LoopbackOllamaTransportTest {
    @Test
    void cancelsOversizedBodyBeforeBufferingTheExcess() {
        var body = new LoopbackOllamaTransport.LimitedBody();
        var cancelled = new AtomicBoolean();
        body.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) { }

            @Override
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(ByteBuffer.allocate(LoopbackOllamaTransport.MAX_RESPONSE_BYTES + 1)));
        assertTrue(cancelled.get());
        var failure = assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
        assertInstanceOf(LoopbackOllamaTransport.OversizedResponse.class, failure.getCause());
    }
}
