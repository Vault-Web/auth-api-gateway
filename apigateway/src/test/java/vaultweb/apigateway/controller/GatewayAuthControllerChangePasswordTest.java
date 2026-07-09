package vaultweb.apigateway.controller;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the protected {@code /auth/change-password} endpoint exposed by {@link
 * GatewayAuthController}. Each test registers and logs in a fresh user to obtain a valid access
 * token before exercising the endpoint, so the full controller -> service -> repository path is
 * covered end to end.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GatewayAuthControllerChangePasswordTest {

  private static final String VALID_PASSWORD = "Test@1234";
  private static final String NEW_VALID_PASSWORD = "NewTest@5678";

  @Autowired private WebTestClient webTestClient;

  private void register(String name, String username, String email, String password) {
    webTestClient
        .post()
        .uri("/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("name", name, "username", username, "email", email, "password", password))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private String loginAndGetAccessToken(String emailUsername, String password) {
    byte[] responseBody =
        webTestClient
            .post()
            .uri("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("emailUsername", emailUsername, "password", password))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();

    return readJsonField(responseBody, "accessToken");
  }

  private WebTestClient.ResponseSpec changePassword(
      String accessToken, String oldPassword, String newPassword) {
    WebTestClient.RequestBodySpec request =
        webTestClient.post().uri("/auth/change-password").contentType(MediaType.APPLICATION_JSON);

    if (accessToken != null) {
      request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
    }

    return request
        .bodyValue(Map.of("oldPassword", oldPassword, "newPassword", newPassword))
        .exchange();
  }

  @Test
  void changePassword_withValidCredentials_returnsOk() {
    register("Karl", "karl", "karl@example.com", VALID_PASSWORD);
    String accessToken = loginAndGetAccessToken("karl@example.com", VALID_PASSWORD);

    changePassword(accessToken, VALID_PASSWORD, NEW_VALID_PASSWORD).expectStatus().isOk();
  }

  @Test
  void changePassword_withoutToken_isUnauthorized() {
    changePassword(null, VALID_PASSWORD, NEW_VALID_PASSWORD).expectStatus().isUnauthorized();
  }

  @Test
  void changePassword_withWrongOldPassword_isUnauthorized() {
    register("Liam", "liam", "liam@example.com", VALID_PASSWORD);
    String accessToken = loginAndGetAccessToken("liam@example.com", VALID_PASSWORD);

    changePassword(accessToken, "Wrong@1234", NEW_VALID_PASSWORD).expectStatus().isUnauthorized();
  }

  @Test
  void changePassword_withWeakNewPassword_isRejected() {
    register("Mona", "mona", "mona@example.com", VALID_PASSWORD);
    String accessToken = loginAndGetAccessToken("mona@example.com", VALID_PASSWORD);

    changePassword(accessToken, VALID_PASSWORD, "weak").expectStatus().isBadRequest();
  }

  @Test
  void changePassword_persistsNewPassword_oldPasswordNoLongerWorks() {
    register("Nina", "nina", "nina@example.com", VALID_PASSWORD);
    String accessToken = loginAndGetAccessToken("nina@example.com", VALID_PASSWORD);

    changePassword(accessToken, VALID_PASSWORD, NEW_VALID_PASSWORD).expectStatus().isOk();

    webTestClient
        .post()
        .uri("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("emailUsername", "nina@example.com", "password", NEW_VALID_PASSWORD))
        .exchange()
        .expectStatus()
        .isOk();

    webTestClient
        .post()
        .uri("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("emailUsername", "nina@example.com", "password", VALID_PASSWORD))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  private static String readJsonField(byte[] json, String field) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(field).asText();
    } catch (Exception e) {
      throw new IllegalStateException("Could not read field '" + field + "' from response", e);
    }
  }
}
