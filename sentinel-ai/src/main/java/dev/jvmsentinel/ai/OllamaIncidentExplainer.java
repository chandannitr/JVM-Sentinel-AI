package dev.jvmsentinel.ai;

import dev.jvmsentinel.detector.Incident;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Optional local-only AI explainer; never takes actions or edits code. */
public final class OllamaIncidentExplainer {
    private final URI endpoint;
    private final String model;
    private final HttpClient client;

    public OllamaIncidentExplainer(URI endpoint, String model) {
        this(endpoint, model, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
    }

    public OllamaIncidentExplainer(URI endpoint, String model, HttpClient client) {
        this.endpoint = Objects.requireNonNull(endpoint);
        this.model = Objects.requireNonNull(model);
        this.client = Objects.requireNonNull(client);
        // This starter intentionally permits only an HTTP loopback endpoint.
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) ||
                !("localhost".equalsIgnoreCase(endpoint.getHost())
                    || "127.0.0.1".equals(endpoint.getHost()))) {
            throw new IllegalArgumentException("Ollama must be on localhost for this starter");
        }
    }

    public String explain(List<Incident> incidents) throws IOException, InterruptedException {
        if (incidents.isEmpty()) return "No incidents detected. AI analysis was skipped.";
        StringBuilder prompt = new StringBuilder("""
                You are a JVM incident analyst. Use ONLY the evidence provided below.
                Separate observations from hypotheses. State uncertainty and propose safe
                diagnostic next steps. Do not invent metrics or claim a root cause is proven.
                Never execute commands, change files, or propose disabling safety controls.

                Incidents:

                """);
        for (Incident incident : incidents) {
            prompt.append("Code: ").append(incident.code()).append("\n")
                    .append("Severity: ").append(incident.severity()).append("\n")
                    .append("Finding: ").append(incident.explanation()).append("\n")
                    .append("Evidence: ").append(incident.evidence()).append("\n\n");
        }
        String requestJson = "{\"model\":" + JsonStrings.quote(model)
                + ",\"stream\":false,\"prompt\":" + JsonStrings.quote(prompt.toString()) + "}";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Ollama request failed (HTTP " + response.statusCode() + ")");
        }
        return JsonStrings.extractStringField(response.body(), "response");
    }
}
