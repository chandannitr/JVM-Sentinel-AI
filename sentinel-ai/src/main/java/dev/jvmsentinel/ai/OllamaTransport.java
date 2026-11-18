package dev.jvmsentinel.ai;

import java.io.IOException;

@FunctionalInterface
interface OllamaTransport {
    Response exchange(String request) throws IOException, InterruptedException;

    record Response(int status, String body) { }
}
