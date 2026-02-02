package com.vibecoding.k8sdoctor;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;


@SpringBootApplication
@EnableCaching
public class K8sDoctorApplication {

    private static final Logger log = LoggerFactory.getLogger(K8sDoctorApplication.class);

    public static void main(String[] args) {
        log.info("==============================================");
        log.info("  K8s Doctor - AI-powered Kubernetes Diagnostics");
        log.info("==============================================");

        // Load .env file and set as system properties
        try {
            Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

            // Set environment variables as system properties
            dotenv.entries().forEach(entry -> {
                System.setProperty(entry.getKey(), entry.getValue());
                log.debug("Loaded environment variable: {}", entry.getKey());
            });

            log.info("Environment variables loaded from .env file");
        } catch (Exception e) {
            log.warn("Failed to load .env file: {}", e.getMessage());
            log.info("Continuing with system environment variables...");
        }

        SpringApplication.run(K8sDoctorApplication.class, args);

        log.info("Application started successfully!");
    }
}
