package io.onedev.server.model.support;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.server.OneDev;
import io.onedev.server.annotation.Editable;
import io.onedev.server.annotation.Password;
import io.onedev.server.rest.annotation.Api;
import nl.altindag.ssl.SSLFactory;

@Editable
public class JevSetting implements Serializable {

    private static final long serialVersionUID = 1L;

    @Api(order=100, description="TypeSafe API key for authentication")
    private String apiKey;

    @Api(order=200, description="Timeout in seconds to get model response")
    private int timeoutSeconds = 30;

    @Editable(order=100, name="API Key", description="Specify your TypeSafe API key for authentication")
    @Password
    @NotEmpty
    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Editable(order=200, name="Timeout", description="Specify how long to wait for the model response in seconds")
    @Min(value=5, message="Timeout should be at least 5 seconds")
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String choose(String state, String instructions, Map<String, String> criteria) throws IOException {
        var client = HttpClient.newBuilder()
            .sslContext(OneDev.getInstance(SSLFactory.class).getSslContext())
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .build();
        return choose(client, state, instructions, criteria);
    }

    String choose(HttpClient client, String state, String instructions, Map<String, String> criteria) throws IOException {
        var objectMapper = new ObjectMapper();
        var questions = Map.of("selection", Map.of(
            "type", "choice",
            "instructions", instructions,
            "criteria", criteria));
        var body = Map.of("model", "jev-latest", "state", state, "questions", questions);
        var request = HttpRequest.newBuilder(URI.create("https://api.typesafe.ai/v1/systemone"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Jev response", e);
        }
        if (response.statusCode() != 200)
            throw new ExplicitException("Jev request failed (HTTP " + response.statusCode() + ")");
        var result = objectMapper.readTree(response.body());
        if (result == null)
            throw new ExplicitException("Jev returned an empty response");
        var choice = result.path("answers").path("selection").path("choice");
        if (!choice.isTextual() || !criteria.containsKey(choice.textValue()))
            throw new ExplicitException("Jev returned an invalid choice");
        return choice.textValue();
    }

}
