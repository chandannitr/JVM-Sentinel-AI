package dev.jvmsentinel.ai;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class LoopbackOllamaTransport implements OllamaTransport {
    static final int MAX_RESPONSE_BYTES = 32 * 1024;
    private static final URI ENDPOINT = URI.create("http://127.0.0.1:11434/api/generate");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    @Override
    public Response exchange(String request) throws IOException, InterruptedException {
        var httpRequest = HttpRequest.newBuilder(ENDPOINT).timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request)).build();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            var pending = client.sendAsync(httpRequest, info -> new LimitedBody());
            try {
                var response = pending.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                String body = StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(response.body())).toString();
                return new Response(response.statusCode(), body);
            } catch (TimeoutException timeout) {
                pending.cancel(true);
                throw new HttpTimeoutException("Ollama request timed out");
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof IOException io) throw io;
                throw new IOException("Ollama transport failed", failure.getCause());
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        } finally {
            client.shutdownNow();
        }
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> body = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int remaining = MAX_RESPONSE_BYTES;

        @Override
        public CompletionStage<byte[]> getBody() { return body.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            body.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > remaining) {
                    subscription.cancel();
                    body.onError(new OversizedResponse());
                    return;
                }
                remaining -= buffer.remaining();
            }
            body.onNext(buffers);
        }

        @Override
        public void onError(Throwable failure) { body.onError(failure); }

        @Override
        public void onComplete() { body.onComplete(); }
    }

    static final class OversizedResponse extends IOException {
        OversizedResponse() { super("Ollama response exceeds 32 KiB"); }
    }
}
