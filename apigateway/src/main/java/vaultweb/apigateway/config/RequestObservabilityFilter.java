package vaultweb.apigateway.config;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/** Request-local context; never hold thread-local scopes across asynchronous work. */
@Component
public class RequestObservabilityFilter implements WebFilter, Ordered {
  public static final String REQUEST_ID = "X-Request-Id";
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Logger log = LoggerFactory.getLogger(RequestObservabilityFilter.class);
  private static final TextMapGetter<HttpHeaders> GETTER =
      new TextMapGetter<>() {
        public Iterable<String> keys(HttpHeaders headers) {
          return headers.keySet();
        }

        public String get(HttpHeaders headers, String key) {
          return headers.getFirst(key);
        }
      };
  private final OpenTelemetry telemetry;

  public RequestObservabilityFilter(OpenTelemetry telemetry) {
    this.telemetry = telemetry;
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    return Mono.defer(
        () -> {
          List<String> values = exchange.getRequest().getHeaders().get(REQUEST_ID);
          String id =
              values != null && values.size() == 1 && SAFE_ID.matcher(values.getFirst()).matches()
                  ? values.getFirst()
                  : UUID.randomUUID().toString();
          String method = exchange.getRequest().getMethod().name();
          Context parent =
              telemetry
                  .getPropagators()
                  .getTextMapPropagator()
                  .extract(Context.root(), exchange.getRequest().getHeaders(), GETTER);
          Span span =
              telemetry
                  .getTracer("vaultweb.apigateway")
                  .spanBuilder("HTTP " + method)
                  .setSpanKind(SpanKind.SERVER)
                  .setParent(parent)
                  .startSpan();
          span.setAttribute("http.request.method", method);
          span.setAttribute("request.id", id);
          Context context = parent.with(span);
          var request =
              exchange
                  .getRequest()
                  .mutate()
                  .headers(
                      headers -> {
                        headers.set(REQUEST_ID, id);
                        headers.remove("traceparent");
                        headers.remove("tracestate");
                        telemetry
                            .getPropagators()
                            .getTextMapPropagator()
                            .inject(context, headers, HttpHeaders::set);
                      })
                  .build();
          exchange.getResponse().getHeaders().set(REQUEST_ID, id);
          // Only method/status/IDs: refresh tokens occur in URL paths; never log URLs or
          // exceptions.
          log.info(
              "request started requestId={} method={} traceId={}",
              id,
              method,
              span.getSpanContext().getTraceId());
          return Mono.defer(() -> chain.filter(exchange.mutate().request(request).build()))
              .doOnError(error -> span.setStatus(StatusCode.ERROR))
              .doFinally(
                  signal -> {
                    var status = exchange.getResponse().getStatusCode();
                    int code =
                        status == null
                            ? (signal == SignalType.ON_COMPLETE ? 200 : 0)
                            : status.value();
                    if (code != 0) span.setAttribute("http.response.status_code", code);
                    if (code >= 500 || signal == SignalType.CANCEL)
                      span.setStatus(StatusCode.ERROR);
                    log.info(
                        "request finished requestId={} method={} status={} signal={} traceId={}",
                        id,
                        method,
                        code,
                        signal,
                        span.getSpanContext().getTraceId());
                    span.end();
                  })
              .contextWrite(
                  reactorContext -> reactorContext.put(REQUEST_ID, id).put(Context.class, context));
        });
  }
}
