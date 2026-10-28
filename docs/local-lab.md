# Native Local Lab Cluster

The native scripts run the Java backend and Vite frontend directly on the host machine,
without Docker. Use them when you want the fastest edit/restart loop or need to debug the
local processes directly.

For the containerized stack, see the Docker Compose flow in the [README](../README.md).

## Prerequisites

- OpenJDK 27 on `PATH`
- Node.js 24 and npm 11 on `PATH`
- `curl`
- Frontend dependencies installed once:

```sh
make frontend-install
```

The backend is built with the repository's `./mvnw` wrapper. The scripts do not use a
global Maven installation.

## Start

Export provider configuration in the shell that starts the lab. The scripts do not load
`.env` automatically.

```sh
export ARL_DEFAULT_PROVIDER=deepseek
export DEEPSEEK_API_KEY='your-deepseek-key'

# Optional: persist stock and idempotency state across restarts
export ARL_DB_PATH="$PWD/target/local/arl.db"

make lab-up
```

`make lab-up`:

1. Validates the local toolchain and ports.
2. Builds `arl-server` and its dependencies.
3. Starts the backend on `http://127.0.0.1:8080`.
4. Starts the Vite frontend on `http://127.0.0.1:5173`.
5. Waits for backend health and frontend readiness.
6. Prints the URLs, log paths, and stop command.

If either process fails to start, the script stops processes it started and prints the log
locations.

## Status and shutdown

```sh
make lab-status
make lab-down
```

`make lab-down` stops the frontend and backend with `SIGTERM`, falls back to `SIGKILL` if
needed, and removes the PID files. Logs are preserved for inspection.

To stop the lab and delete its local logs/PID directory:

```sh
make lab-clean
```

## Configuration

The native scripts use fixed ports because the Vite development proxy targets the backend
directly:

| Setting | Default | Notes |
|---|---|---|
| API | `http://127.0.0.1:8080` | Set by `application.yml` or `ARL_PORT`; native scripts require port 8080 |
| UI | `http://127.0.0.1:5173` | Vite development server |
| Run files | `.arl-run/` | Override with `ARL_RUN_DIR` |
| Database | in-memory | Set `ARL_DB_PATH` to a file path for persistence |
| Provider | `deepseek` | Set `ARL_DEFAULT_PROVIDER=grok` for Grok |
| DeepSeek key | unset | `DEEPSEEK_API_KEY` |
| Grok key | unset | `XAI_API_KEY` |
| Timeout | 30 seconds | `ARL_LLM_TIMEOUT_SECONDS` |
| Temperature | provider default | `ARL_LLM_TEMPERATURE`; DeepSeek only |

If `ARL_PORT` is set to anything other than `8080`, `make lab-up` exits with an error
because the current Vite proxy is pinned to `127.0.0.1:8080`. Use the Docker stack or update
both `application.yml` and `frontend/vite.config.ts` if you need different ports.

## Logs and lifecycle

- Backend log: `.arl-run/backend.log`
- Frontend log: `.arl-run/frontend.log`
- PID files: `.arl-run/backend.pid` and `.arl-run/frontend.pid`

The scripts only stop processes whose command line matches the expected backend/vite
process. A stale PID file is removed rather than killing an unrelated process. `make
lab-up` refuses to start if either port is already occupied by an unknown process.

## Security

The native lab is an unauthenticated local demo. It binds loopback addresses and must not
be exposed to an untrusted network. Provider keys remain in the environment of the started
processes; do not put them in scripts, source files, or committed `.env` files. See
[Security](../SECURITY.md).

## Troubleshooting

| Symptom | Action |
|---|---|
| `frontend dependencies are missing` | Run `make frontend-install` |
| `port 8080 is already in use` | Stop the Docker demo or the other process using the port |
| `port 5173 is already in use` | Stop the other Vite process or change the port consistently in both configs |
| Backend exits immediately | Check `.arl-run/backend.log`; verify Java 27 and `ARL_DB_PATH` permissions |
| `PROVIDER_NOT_CONFIGURED` | Export the provider key in the same shell, then restart with `make lab-down && make lab-up` |
| Stale process after a crash | Run `make lab-status`, then `make lab-down`; remove `.arl-run/` only if the PID files are stale |
| Frontend loads but API calls fail | Confirm backend health at `http://127.0.0.1:8080/health` and that Vite is using port 5173 |

## Difference from the Docker demo

| Native scripts | Docker Compose demo |
|---|---|
| Java and Node run on the host | Services run in containers |
| Frontend at port 5173 | Frontend at port 8088 |
| In-memory SQLite by default | Persistent named volume `arl-data` |
| Logs in `.arl-run/` | Logs via `docker compose logs` |
| No image rebuild required | `make demo` builds images |
