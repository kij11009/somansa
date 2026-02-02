package com.vibecoding.k8sdoctor.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


@Configuration
@ConfigurationProperties(prefix = "openrouter")
@Data
public class OpenRouterConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterConfig.class);

    private String apiKey;
    private String apiUrl = "https://openrouter.ai/api/v1/chat/completions";
    private String model;
    private Integer timeout = 30000;
    private Integer maxTokens = 2000;
    private Double temperature = 0.7;

    @PostConstruct
    public void init() {
        // .env에서 API 키 로드 (시스템 프로퍼티 우선, 환경변수 대체)
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("OPENROUTER_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENROUTER_API_KEY");
        }

        // modelkey.txt에서 모델 로드
        if (model == null || model.isBlank()) {
            model = loadModelFromFile();
        }

        validateConfig();
    }

    private String loadModelFromFile() {
        try {
            Path modelKeyPath = Paths.get("modelkey.txt");
            if (Files.exists(modelKeyPath)) {
                String modelName = Files.readString(modelKeyPath).trim();
                log.info("Loaded model from modelkey.txt: {}", modelName);
                return modelName;
            }
        } catch (IOException e) {
            log.warn("Failed to read modelkey.txt: {}", e.getMessage());
        }

        // Fallback 모델
        String fallbackModel = "arcee-ai/trinity-large-preview:free";
        log.info("Using fallback model: {}", fallbackModel);
        return fallbackModel;
    }

    public void validateConfig() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("OPENROUTER_API_KEY is not configured!");
            throw new IllegalStateException("OPENROUTER_API_KEY must be set in .env file or environment variables");
        }

        log.info("OpenRouter configuration validated successfully");
        log.info("  - API URL: {}", apiUrl);
        log.info("  - Model: {}", model);
        log.info("  - Timeout: {}ms", timeout);
        log.info("  - API Key: {}...", apiKey.substring(0, Math.min(10, apiKey.length())));
    }
}
