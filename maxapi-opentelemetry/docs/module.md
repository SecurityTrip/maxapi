# Module maxapi-opentelemetry

OpenTelemetry tracing for maxapi: [MaxTracing][ru.securitytrip.maxapi.opentelemetry.MaxTracing] records
API requests as client spans and dispatched updates as consumer spans, following the semantic
conventions. The span of an update is current in the coroutines of its handler, so the requests the
handler makes are its children.

# Package ru.securitytrip.maxapi.opentelemetry

The `MaxEventListener` that records spans.
