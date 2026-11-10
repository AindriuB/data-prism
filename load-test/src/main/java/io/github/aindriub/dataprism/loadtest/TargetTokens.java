package io.github.aindriub.dataprism.loadtest;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Mints the tokens a run against an already-running target presents, by asking the target's issuer ({@code POST
 * /token}, the shape the quickstart issuer has) for one in the {@code investigator} role and purpose
 * {@code investigation}, in the case the rotation names. A failure says only the status: the issuer's body, which
 * may hold a token, is never put in a message.
 */
final class TargetTokens implements CredentialRotation.Minter {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final URI tokenUrl;
    private final int ttlMinutes;
    private final HttpClient client = LoadDriver.boundConnect(HttpClient.newBuilder()).build();

    TargetTokens(Scenario.Target target) {
        this.tokenUrl = URI.create(target.tokenUrl());
        this.ttlMinutes = target.tokenLifetimeMinutes();
    }

    /** The request body asking for a token in {@code caseId}. */
    static String requestBody(String caseId, int ttlMinutes) {
        return MAPPER.writeValueAsString(Map.of("roles", List.of("investigator"), "purpose", "investigation",
                "caseId", caseId, "ttlMinutes", ttlMinutes));
    }

    /** The {@code access_token} of an issuer's answer. */
    static String accessToken(String answer) {
        String token = MAPPER.readTree(answer).path("access_token").asString("");
        if (token.isEmpty()) {
            throw new IllegalStateException("the token issuer's answer holds no access_token");
        }
        return token;
    }

    @Override
    public String mint(Duration lifetime, String caseId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(tokenUrl).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(caseId, ttlMinutes))).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("the token issuer answered status " + response.statusCode());
        }
        return accessToken(response.body());
    }
}
