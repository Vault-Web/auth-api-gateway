package vaultweb.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RequestObservabilityFilterTest {
  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final OpenTelemetrySdk telemetry =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                  .build())
          .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
          .build();
  private final RequestObservabilityFilter filter = new RequestObservabilityFilter(telemetry);

  @AfterEach
  void close() {
    telemetry.close();
  }

  @Test
  void concurrentRequestsKeepTheirOwnHeadersAndReactiveContext() {
    List<String> seen = new CopyOnWriteArrayList<>();
    Flux.range(0, 20)
        .flatMap(
            index -> {
              String id = "request-" + index;
              var exchange =
                  MockServerWebExchange.from(
                      MockServerHttpRequest.get("/auth/refresh/secret-token?secret=query")
                          .header("X-Request-Id", id));
              return filter
                  .filter(
                      exchange,
                      downstream ->
                          Mono.delay(Duration.ofMillis(5))
                              .then(
                                  Mono.deferContextual(
                                      context -> {
                                        assertThat(context.<String>get("X-Request-Id"))
                                            .isEqualTo(id);
                                        assertThat(
                                                downstream
                                                    .getRequest()
                                                    .getHeaders()
                                                    .getFirst("X-Request-Id"))
                                            .isEqualTo(id);
                                        assertThat(
                                                downstream
                                                    .getRequest()
                                                    .getHeaders()
                                                    .getFirst("traceparent"))
                                            .contains(
                                                io.opentelemetry.api.trace.Span.fromContext(
                                                        context.get(Context.class))
                                                    .getSpanContext()
                                                    .getTraceId());
                                        seen.add(id);
                                        downstream
                                            .getResponse()
                                            .setStatusCode(HttpStatus.UNAUTHORIZED);
                                        return downstream.getResponse().setComplete();
                                      })))
                  .then(
                      Mono.fromRunnable(
                          () ->
                              assertThat(
                                      exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
                                  .isEqualTo(id)));
            })
        .blockLast();
    assertThat(seen).hasSize(20).doesNotHaveDuplicates();
    assertThat(exporter.getFinishedSpanItems())
        .hasSize(20)
        .allSatisfy(
            span -> {
              assertThat(span.getName()).isEqualTo("HTTP GET");
              assertThat(
                      span.getAttributes().get(AttributeKey.longKey("http.response.status_code")))
                  .isEqualTo(401L);
              assertThat(span.toString()).doesNotContain("secret-token", "secret=query");
            });
  }

  @Test
  void invalidOrAmbiguousIdsAreReplacedWithoutRejectingRequests() {
    for (String[] ids :
        List.of(
            new String[] {""},
            new String[] {"bad id"},
            new String[] {"a".repeat(129)},
            new String[] {"one", "two"})) {
      var exchange =
          MockServerWebExchange.from(MockServerHttpRequest.get("/").header("X-Request-Id", ids));
      filter.filter(exchange, downstream -> downstream.getResponse().setComplete()).block();
      assertThat(exchange.getResponse().getHeaders().getFirst("X-Request-Id"))
          .matches("[0-9a-f-]{36}");
    }
  }

  @Test
  void errorsEndSpansWithoutRecordingSensitiveExceptions() {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/"));
    assertThatThrownBy(
            () ->
                filter
                    .filter(
                        exchange,
                        downstream -> Mono.error(new IllegalStateException("secret-value")))
                    .block())
        .isInstanceOf(IllegalStateException.class);
    assertThat(exporter.getFinishedSpanItems())
        .singleElement()
        .satisfies(
            span -> {
              assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
              assertThat(span.getEvents()).isEmpty();
              assertThat(span.toString()).doesNotContain("secret-value");
            });
  }

  @Test
  void cancellationEndsSpanAndLogsExcludeUrlSecrets() {
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(RequestObservabilityFilter.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/auth/refresh/token-secret?q=query-secret")
                  .header("X-Request-Id", "cancel-42"));
      var subscription = filter.filter(exchange, downstream -> Mono.never()).subscribe();
      subscription.dispose();
      assertThat(exporter.getFinishedSpanItems())
          .singleElement()
          .satisfies(
              span -> {
                assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
                assertThat(
                        span.getAttributes().get(AttributeKey.longKey("http.response.status_code")))
                    .isNull();
              });
      assertThat(appender.list)
          .hasSize(2)
          .allSatisfy(
              event ->
                  assertThat(event.getFormattedMessage())
                      .contains("requestId=cancel-42")
                      .doesNotContain("token-secret", "query-secret"));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
