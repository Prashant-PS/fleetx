package com.fleetboard.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The shared Claude client. Null (AI off) unless {@code fleet.ai.enabled} is true and ANTHROPIC_API_KEY or
 * ANTHROPIC_AUTH_TOKEN is set; the SDK reads the key and ANTHROPIC_BASE_URL from the environment.
 */
@Component
public class ClaudeClientProvider {

    private final AnthropicClient client;
    private final String model;

    public ClaudeClientProvider(@Value("${fleet.ai.enabled:true}") boolean enabled,
                                @Value("${fleet.ai.model:claude-opus-5-5}") String model) {
        this.model = model;
        boolean hasCredentials = notBlank(System.getenv("ANTHROPIC_API_KEY"))
                || notBlank(System.getenv("ANTHROPIC_AUTH_TOKEN"));
        this.client = enabled && hasCredentials ? AnthropicOkHttpClient.fromEnv() : null;
    }

    /** The client, or null when AI is off. */
    public AnthropicClient client() {
        return client;
    }

    public boolean enabled() {
        return client != null;
    }

    public String model() {
        return model;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
