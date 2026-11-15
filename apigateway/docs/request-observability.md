# Gateway request observability

Every request, including authentication failures, receives `X-Request-Id` in its
response. A single caller value of 1–128 ASCII letters/digits/dot/underscore/hyphen
(starting with a letter or digit) is accepted. Missing, invalid or multiple values
are replaced with a UUID. IDs are diagnostic labels, never authentication evidence.

An OpenTelemetry server span surrounds each request. W3C `traceparent`/`tracestate`
are extracted and the server span context is propagated in outgoing Gateway route
headers. WebClients constructed from Spring's injected `WebClient.Builder` also
propagate the ID and span context from Reactor context. Background work without
request context is unaffected. There are currently no production downstream routes;
tests use a disposable route and HTTP stub, without introducing service routing.

Request start/end and exception-handler log lines include explicit `requestId`
fields. Context is request-local, with no MDC scope held over asynchronous work.
New telemetry records method, status, IDs and completion signal, without URL paths,
query strings, bodies, authorization headers or exception messages. Refresh URLs
contain tokens; validation exceptions can contain passwords. Exception-handler
logging therefore records only exception type and ID; HTTP error payloads retain
their existing contract. Caller IDs should not contain secrets.

The Boot-managed OpenTelemetry SDK creates spans without a network exporter.
Application `SpanProcessor` beans can consume them, and a supplied `OpenTelemetry`
bean replaces the default SDK. Collector/exporter configuration is a follow-up;
this change does not contact a collector or install a global SDK. Sampling follows
the SDK's default parent-based policy, including upstream unsampled traces.

## Validation

On Linux with Java 21, from `apigateway/`:

```sh
SPRING_PROFILES_ACTIVE=test bash ./mvnw -B spotless:check verify
```

The isolated test profile uses H2 and a synthetic signing value. Existing CI still
skips tests; changing repository workflows requires a separate scoped owner task.
H2 checks do not prove PostgreSQL migration compatibility.

## Impact and rollback

This is standalone gateway instrumentation. No database, stored token, session,
auth payload, environment variable or deployment-pointer migration is needed.
Response IDs and outgoing diagnostic headers are additive. No production routing
adoption is included. Existing core/Cloud login must continue through its current
routes. Reverting this change removes instrumentation/diagnostic headers; no data
conversion is needed, and already collected telemetry is governed by its retention
policy. Review collector access/retention before a future exporter rollout.

References: [Boot 3.5 tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html),
[OpenTelemetry SDK](https://opentelemetry.io/docs/languages/java/sdk/).
