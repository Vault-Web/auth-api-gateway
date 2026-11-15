package vaultweb.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties =
        "auth.publicUrls=/auth/login,/auth/register,/auth/reset-password,/auth/refresh/**,/test-route")
@ActiveProfiles("test")
@Import(ObservabilityIntegrationTest.Fixture.class)
class ObservabilityIntegrationTest {
  @org.springframework.boot.test.web.server.LocalServerPort private int port;
  @Autowired private InMemorySpanExporter exporter;
  @Autowired private WebClient.Builder clientBuilder;

  @Test
  void actualGatewayRoutePropagatesIdAndTraceWithoutChangingResponse() {
    String parentTrace = "0123456789abcdef0123456789abcdef";
    WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .get()
        .uri("/test-route")
        .header("X-Request-Id", "route-42")
        .header("traceparent", "00-" + parentTrace + "-0123456789abcdef-01")
        .exchange()
        .expectStatus()
        .isOk()
        .expectHeader()
        .valueEquals("X-Request-Id", "route-42")
        .expectBody(String.class)
        .value(body -> assertThat(body).startsWith("route-42|00-" + parentTrace));
    awaitSpan("route-42");
    assertThat(exporter.getFinishedSpanItems())
        .anySatisfy(
            span -> {
              assertThat(span.getTraceId()).isEqualTo(parentTrace);
              assertThat(span.getParentSpanId()).isEqualTo("0123456789abcdef");
            });
  }

  @Test
  void unauthorizedRequestsStillHaveIdsAndSpans() {
    WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .get()
        .uri("/private")
        .header("X-Request-Id", "denied-42")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .valueEquals("X-Request-Id", "denied-42");
    awaitSpan("denied-42");
  }

  @Test
  void customizedWebClientPropagatesReactiveContext() {
    var exchange =
        org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/")
                .header("X-Request-Id", "client-42"));
    StringBuilder result = new StringBuilder();
    // Use the application's telemetry to exercise the same context contract as incoming requests.
    filter
        .filter(
            exchange,
            downstream ->
                clientBuilder
                    .build()
                    .get()
                    .uri("http://localhost:" + stub.port())
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnNext(result::append)
                    .then())
        .block();
    assertThat(result.toString()).startsWith("client-42|00-");
  }

  @Autowired private RequestObservabilityFilter filter;
  @Autowired private DisposableServer stub;

  @Test
  void validationLogsCarryIdWithoutRejectedPassword() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(
                vaultweb.apigateway.exceptions.GlobalExceptionHandler.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      WebTestClient.bindToServer()
          .baseUrl("http://localhost:" + port)
          .build()
          .post()
          .uri("/auth/login")
          .header("X-Request-Id", "validation-42")
          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
          .bodyValue(java.util.Map.of("emailUsername", "test", "password", ""))
          .exchange()
          .expectStatus()
          .isBadRequest();
      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getFormattedMessage())
                    .contains("requestId=validation-42", "exceptionType=WebExchangeBindException")
                    .doesNotContain("rejected value", "password");
                assertThat(event.getThrowableProxy()).isNull();
              });
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  private void awaitSpan(String id) {
    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(exporter.getFinishedSpanItems())
                    .anySatisfy(
                        span ->
                            assertThat(
                                    span.getAttributes()
                                        .get(
                                            io.opentelemetry.api.common.AttributeKey.stringKey(
                                                "request.id")))
                                .isEqualTo(id)));
  }

  @TestConfiguration
  static class Fixture {
    @Bean(destroyMethod = "disposeNow")
    DisposableServer stub() {
      return HttpServer.create()
          .host("127.0.0.1")
          .port(0)
          .handle(
              (request, response) ->
                  response.sendString(
                      Mono.just(
                          request.requestHeaders().get("X-Request-Id")
                              + "|"
                              + request.requestHeaders().get("traceparent"))))
          .bindNow();
    }

    @Bean
    RouteLocator testRoutes(RouteLocatorBuilder builder, DisposableServer stub) {
      return builder
          .routes()
          .route(
              "observability-fixture",
              route -> route.path("/test-route").uri("http://127.0.0.1:" + stub.port()))
          .build();
    }

    @Bean
    InMemorySpanExporter exporter() {
      return InMemorySpanExporter.create();
    }

    @Bean
    SpanProcessor testProcessor(InMemorySpanExporter exporter) {
      return SimpleSpanProcessor.create(exporter);
    }
  }
}
