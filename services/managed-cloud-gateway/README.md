# Managed Hy-MT2 cloud gateway

This service is the public boundary between the Online APK and a private
`llama-server` running the pinned Hy-MT2 1.8B Q4_K_M model. The Android app
does not contain an upstream provider key. The gateway:

- accepts only `POST /v1/chat/completions` for public model ID
  `hymt2-1.8b-q4`;
- validates the fixed multilingual-to-Chinese prompt and deterministic decode
  settings used by the project benchmark;
- replaces the public model ID with the private upstream model ID;
- reads an optional upstream bearer key only from the server environment;
- caps request/response sizes, concurrent requests, and requests per client IP;
- does not log request or translated text.

TLS must terminate at the hosting platform or a reverse proxy. Expose only the
gateway; keep `llama-server` on a private network.

## Environment

| Variable | Default | Purpose |
| --- | --- | --- |
| `LISTEN_ADDRESS` | `:8080` | Gateway listener |
| `UPSTREAM_CHAT_URL` | `http://127.0.0.1:8081/v1/chat/completions` | Private llama.cpp endpoint |
| `UPSTREAM_HEALTH_URL` | `http://127.0.0.1:8081/health` | Readiness probe target |
| `UPSTREAM_MODEL` | `Hy-MT2-1.8B-Q4_K_M` | Private upstream model ID |
| `UPSTREAM_API_KEY` | empty | Optional private upstream bearer key |
| `RATE_LIMIT_PER_MINUTE` | `120` | Per-client fixed-window limit |
| `MAX_IN_FLIGHT` | `8` | Public requests admitted at once |
| `MAX_INPUT_CHARS` | `6000` | OCR text limit |
| `REQUEST_TIMEOUT_SECONDS` | `60` | Upstream timeout |
| `TRUST_PROXY_HEADERS` | `false` | Trust first `X-Forwarded-For` address |

Set `TRUST_PROXY_HEADERS=true` only when direct access is blocked and a trusted
reverse proxy overwrites the header.

## Endpoints

- `GET /healthz`: process health
- `GET /readyz`: private model-server readiness
- `GET /metrics`: small Prometheus counter set
- `GET /v1/models`: the single public model
- `POST /v1/chat/completions`: fixed translation contract

## Build and test

```bash
go test ./...
docker build -t screen-translation-managed-gateway .
```

Example runtime wiring, with values supplied by the deployment platform:

```bash
docker run --rm -p 127.0.0.1:8080:8080 \
  -e UPSTREAM_CHAT_URL=http://LLAMA_HOST:8081/v1/chat/completions \
  -e UPSTREAM_HEALTH_URL=http://LLAMA_HOST:8081/health \
  -e UPSTREAM_MODEL=Hy-MT2-1.8B-Q4_K_M \
  -e UPSTREAM_API_KEY=SERVER_SIDE_TOKEN \
  screen-translation-managed-gateway
```

The Online APK is built with the public HTTPS base URL:

```bash
./gradlew assembleOnlineRelease \
  -PmanagedCloudBaseUrl=https://PUBLIC_GATEWAY/v1
```
