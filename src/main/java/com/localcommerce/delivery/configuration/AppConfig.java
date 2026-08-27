package com.localcommerce.delivery.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig {

    /**
     * Comma-separated list of allowed origins for the admin React dashboard.
     * Defaults to http://localhost:5173 for local development.
     * Set ADMIN_CORS_ALLOWED_ORIGINS env var to add/replace origins in production.
     * Example: http://localhost:5173,https://admin.yourdomain.com
     */
    @Value("${admin.cors.allowed.origins:http://localhost:5173}")
    private String adminCorsAllowedOrigins;

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * CORS configuration for the admin React dashboard.
     * Scoped to /api/admin/** only — webhook endpoints are intentionally excluded.
     * CORS is browser-enforcement only and has no effect on server-to-server calls
     * (curl, Postman, Meta webhook POSTs).
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/admin/**")
                        .allowedOrigins(adminCorsAllowedOrigins.split(","))
                        .allowedMethods("GET", "POST", "PATCH")
                        .allowedHeaders("X-Admin-API-Key", "X-Admin-User", "Content-Type")
                        .allowCredentials(false);
            }
        };
    }
}