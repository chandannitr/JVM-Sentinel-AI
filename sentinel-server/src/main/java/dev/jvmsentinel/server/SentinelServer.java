package dev.jvmsentinel.server;

import dev.jvmsentinel.history.FileIncidentHistory;
import dev.jvmsentinel.history.IncidentHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@SpringBootApplication(proxyBeanMethods = false)
public class SentinelServer {
    public static void main(String[] args) {
        SpringApplication.run(SentinelServer.class, args);
    }

    @Bean
    IncidentHistoryRepository history(@Value("${sentinel.history-dir}") String directory) {
        if (directory.isBlank()) throw new IllegalArgumentException("History directory is required");
        return new FileIncidentHistory(Path.of(directory));
    }
}
