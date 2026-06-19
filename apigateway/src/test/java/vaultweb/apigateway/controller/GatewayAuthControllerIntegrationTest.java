package vaultweb.apigateway.controller;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Integration tests for the public authentication endpoints exposed by {@link
 * GatewayAuthController} ({@code /auth/register}, {@code /auth/login}, {@code /auth/refresh}). The
 * gateway is started with a random port and backed by an in-memory R2DBC database so the full
 * controller -&gt; service -&gt; repository path is exercised end to end.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GatewayAuthControllerIntegrationTest {

  private static final String VALID_PASSWORD = "Test@1234";

  @Autowired private WebTestClient webTestClient;

  private static Map<String, String> registrationBody(
      String name, String username, String email, String password) {
    Map<String, String> body = new HashMap<>();
    body.put("name", name);
    body.put("username", username);
    body.put("email", email);
    body.put("password", password);
    return body;
  }

  private WebTestClient.ResponseSpec register(
      String name, String username, String email, String password) {
    return webTestClient
        .post()
        .uri("/auth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(registrationBody(name, username, email, password))
        .exchange();
  }

  private WebTestClient.ResponseSpec login(String emailUsername, String password) {
    return webTestClient
        .post()
        .uri("/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("emailUsername", emailUsername, "password", password))
        .exchange();
  }

  // ----- /auth/register -----

  @Test
  void register_withValidPayload_returnsCreatedUserDetails() {
    register("Alice", "alice", "alice@example.com", VALID_PASSWORD)
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.username")
        .isEqualTo("alice")
        .jsonPath("$.email")
        .isEqualTo("alice@example.com")
        .jsonPath("$.name")
        .isEqualTo("Alice")
        .jsonPath("$.password")
        .doesNotExist();
  }

  @Test
  void register_withDuplicateEmail_isRejected() {
    register("Bob", "bob", "dup-email@example.com", VALID_PASSWORD).expectStatus().isCreated();

    register("Bobby", "bobby", "dup-email@example.com", VALID_PASSWORD)
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void register_withDuplicateUsername_isRejected() {
    register("Carol", "carol", "carol@example.com", VALID_PASSWORD).expectStatus().isCreated();

    register("Caroline", "carol", "carol2@example.com", VALID_PASSWORD)
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void register_withInvalidEmailFormat_isRejected() {
    register("Dave", "dave", "not-an-email", VALID_PASSWORD).expectStatus().isBadRequest();
  }

  @Test
  void register_withWeakPassword_isRejected() {
    register("Erin", "erin", "erin@example.com", "weak").expectStatus().isBadRequest();
  }

  @Test
  void register_withMissingName_isRejected() {
    register("", "frank", "frank@example.com", VALID_PASSWORD).expectStatus().isBadRequest();
  }

  // ----- /auth/login -----

  @Test
  void login_withValidEmailCredentials_returnsTokens() {
    register("Grace", "grace", "grace@example.com", VALID_PASSWORD).expectStatus().isCreated();

    login("grace@example.com", VALID_PASSWORD)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.accessToken")
        .exists()
        .jsonPath("$.refreshToken")
        .exists();
  }

  @Test
  void login_withValidUsernameCredentials_returnsTokens() {
    register("Heidi", "heidi", "heidi@example.com", VALID_PASSWORD).expectStatus().isCreated();

    login("heidi", VALID_PASSWORD)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.accessToken")
        .exists();
  }

  @Test
  void login_withWrongPassword_isUnauthorized() {
    register("Ivan", "ivan", "ivan@example.com", VALID_PASSWORD).expectStatus().isCreated();

    login("ivan@example.com", "Wrong@1234").expectStatus().isUnauthorized();
  }

  @Test
  void login_withNonExistentUser_isUnauthorized() {
    login("ghost@example.com", VALID_PASSWORD).expectStatus().isUnauthorized();
  }

  @Test
  void login_withMissingPassword_isRejected() {
    login("someone@example.com", "").expectStatus().isBadRequest();
  }

  // ----- /auth/refresh -----

  @Test
  void refresh_withTokenFromLogin_returnsNewTokens() {
    register("Judy", "judy", "judy@example.com", VALID_PASSWORD).expectStatus().isCreated();

    byte[] loginResponse =
        login("judy@example.com", VALID_PASSWORD)
            .expectStatus()
            .isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();

    String refreshToken = readJsonField(loginResponse, "refreshToken");

    webTestClient
        .get()
        .uri("/auth/refresh/{token}", refreshToken)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.accessToken")
        .exists()
        .jsonPath("$.refreshToken")
        .exists();
  }

  private static String readJsonField(byte[] json, String field) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get(field).asText();
    } catch (Exception e) {
      throw new IllegalStateException("Could not read field '" + field + "' from response", e);
    }
  }
}
