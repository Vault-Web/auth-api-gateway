package vaultweb.apigateway.config;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import reactor.core.publisher.Mono;

@Configuration
public class CorrelationClientConfig {
  @Bean
  public WebClientCustomizer correlationClientCustomizer(OpenTelemetry telemetry) {
    return builder ->
        builder.filter(
            (request, next) ->
                Mono.deferContextual(
                    context -> {
                      if (!context.hasKey(RequestObservabilityFilter.REQUEST_ID))
                        return next.exchange(request);
                      var outgoing =
                          ClientRequest.from(request)
                              .headers(
                                  headers -> {
                                    headers.set(
                                        RequestObservabilityFilter.REQUEST_ID,
                                        context.get(RequestObservabilityFilter.REQUEST_ID));
                                    headers.remove("traceparent");
                                    headers.remove("tracestate");
                                    telemetry
                                        .getPropagators()
                                        .getTextMapPropagator()
                                        .inject(
                                            context.getOrDefault(Context.class, Context.root()),
                                            headers,
                                            (carrier, key, value) -> carrier.set(key, value));
                                  })
                              .build();
                      return next.exchange(outgoing);
                    }));
  }
}
