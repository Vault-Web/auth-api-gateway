package vaultweb.apigateway.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;

@Configuration
public class TracingConfig {
  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(OpenTelemetry.class)
  public OpenTelemetrySdk gatewayTelemetry(ObjectProvider<SpanProcessor> processors) {
    var provider = SdkTracerProvider.builder();
    processors.orderedStream().forEach(provider::addSpanProcessor);
    return OpenTelemetrySdk.builder()
        .setTracerProvider(provider.build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build();
  }
}
