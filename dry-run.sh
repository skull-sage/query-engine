#!/usr/bin/env bash
#
# dry-run.sh — stand up the domain query services and the Query Builder UI for trying things out,
# either in the browser or through each service's http/*.http files.
#
#   Domain     Service           Service URL             Postgres (host port)
#   order      order-query       http://localhost:8081   localhost:5433  db order_query, admin login postgres/postgres
#   inventory  inventory-query   http://localhost:8082   localhost:5434  db inventory_query, admin login postgres/postgres
#   UI         query-builder-ui  http://localhost:5173
#
# Each service connects to its database with one plain login (order_query / inventory_query, created by the
# database's docker/init scripts), and that is the only login query-engine uses: it creates its table
# (qe_stored_query) there, and runs authors' queries on a second pool of the same datasource inside a read-only
# transaction (see docs/security_protocol.md).
#
# Steps:
#   1. Publish query-engine to the local Maven repository, then build each selected service's
#      boot jar. Each project is an independent Gradle build; services depend on the published
#      com.brac.query:query-engine artifact.
#   2. For each selected domain, start its Postgres (its docker/init scripts create the service's login and seed
#      that domain's projection data), then start the service container and wait until it answers.
#   3. Start the Query Builder UI (Vite dev server) and open it in the browser.
#
# Each service runs in its own container so you can check it holds no in-memory state: with
# everything up, stop and start one service container while its Postgres keeps running, e.g.
#   docker compose -f order-query/docker/docker-compose.yml stop order-query
#   docker compose -f order-query/docker/docker-compose.yml start order-query
# and confirm its drafts and published queries are still there.
#
# Usage:
#   ./dry-run.sh                     # both domains + UI, opens the browser
#   ./dry-run.sh --domain order      # only the given domain (repeatable: --domain order --domain inventory)
#   ./dry-run.sh --db-only           # only the selected domains' Postgres containers
#   ./dry-run.sh --no-frontend       # services only, skip the UI
#   ./dry-run.sh --no-open           # start everything but don't open the browser
#   ./dry-run.sh --rebuild           # clean Gradle build + docker image rebuild without cache
#
# ./stop.sh tears everything down (and resets the databases).
set -euo pipefail

cd "$(dirname "$0")"
WORKSPACE_DIR="$(pwd)"
UI_DIR="${WORKSPACE_DIR}/query-builder-ui"
UI_PORT=5173
UI_LOG="${WORKSPACE_DIR}/.dry-run-frontend.log"
UI_PID_FILE="${WORKSPACE_DIR}/.dry-run-frontend.pid"
FRONTEND_URL="http://localhost:${UI_PORT}"
UI_READY_TIMEOUT_SECONDS="${UI_READY_TIMEOUT_SECONDS:-40}"
ALL_DOMAINS="order inventory"
# shellcheck source=scripts/ui-dev-server.sh
source "${WORKSPACE_DIR}/scripts/ui-dev-server.sh"

# If this run starts the dev server and then fails or is interrupted before it's ready, stop
# what it started instead of leaving it running in the background.
UI_STARTED_BY_THIS_RUN=0
UI_READY=0
on_exit() {
  local rc=$?
  if [[ "${UI_STARTED_BY_THIS_RUN}" -eq 1 && "${UI_READY}" -ne 1 ]]; then
    echo "==> dry-run.sh didn't finish; stopping the UI dev server it started ..." >&2
    ui_stop_all >&2
  fi
  exit "${rc}"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---- per-domain settings (functions instead of associative arrays: macOS ships bash 3.2) ----
service_of()  { echo "$1-query"; }
port_of()     { case "$1" in order) echo 8081 ;; inventory) echo 8082 ;; esac; }
db_port_of()  { case "$1" in order) echo 5433 ;; inventory) echo 5434 ;; esac; }
compose_of()  { echo "${WORKSPACE_DIR}/$(service_of "$1")/docker/docker-compose.yml"; }
compose()     { local domain="$1"; shift; docker compose -f "$(compose_of "${domain}")" "$@"; }

DOMAINS=""
DB_ONLY=0
REBUILD=0
NO_FRONTEND=0
NO_OPEN=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain)
      [[ $# -ge 2 ]] || { echo "--domain needs a value: order | inventory" >&2; exit 2; }
      case "$2" in
        order|inventory) DOMAINS="${DOMAINS} $2" ;;
        *) echo "Unknown domain '$2'. Use: order | inventory" >&2; exit 2 ;;
      esac
      shift 2 ;;
    --db-only) DB_ONLY=1; shift ;;
    --rebuild) REBUILD=1; shift ;;
    --no-frontend) NO_FRONTEND=1; shift ;;
    --no-open) NO_OPEN=1; shift ;;
    *) echo "Unknown option '$1'. Usage: $0 [--domain order|inventory]... [--db-only] [--no-frontend] [--no-open] [--rebuild]" >&2; exit 2 ;;
  esac
done
DOMAINS="${DOMAINS:-${ALL_DOMAINS}}"

echo "==> Workspace: ${WORKSPACE_DIR}"
echo "==> Domains:  ${DOMAINS# }"

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker isn't running. Start Docker Desktop and try again." >&2
  exit 1
fi

# ---- 1. start each selected domain's Postgres ----
for domain in ${DOMAINS}; do
  echo "==> [${domain}] Starting Postgres (seeded with ${domain} projection data) ..."
  compose "${domain}" up -d --wait postgres
  echo "    -> localhost:$(db_port_of "${domain}") (db $(service_of "${domain}" | tr - _), admin login postgres/postgres)"
done

# Each project is an independent Gradle build. query-engine is published to the local Maven
# repository (~/.m2) and each service resolves it from there as com.brac.query:query-engine.
# Always run the builds: Gradle's up-to-date checks make them no-ops when nothing changed, and
# they guarantee the images are built from the checked-out code (a leftover jar from before a
# `git pull` once got served instead of the pulled code).
gradle_build() {
  local dir="$1"; shift
  chmod +x "${WORKSPACE_DIR}/${dir}/gradlew"
  if [[ "${REBUILD}" -eq 1 ]]; then
    ( cd "${WORKSPACE_DIR}/${dir}" && ./gradlew clean "$@" --no-daemon -q )
  else
    ( cd "${WORKSPACE_DIR}/${dir}" && ./gradlew "$@" --no-daemon -q )
  fi
}

publish_engine() {
  echo "==> Publishing query-engine to the local Maven repository ..."
  gradle_build query-engine publishToMavenLocal
}

if [[ "${DB_ONLY}" -eq 1 ]]; then
  # Publish the engine too, so `./gradlew bootRun` in a service works against just the database container.
  publish_engine
  echo "==> --db-only set: skipping services and UI."
  exit 0
fi

# ---- 2. publish query-engine locally, build each selected service ----
publish_engine
for domain in ${DOMAINS}; do
  echo "==> Building $(service_of "${domain}") ..."
  gradle_build "$(service_of "${domain}")" bootJar
done

# ---- 3. build and start each service container, then wait for it ----
for domain in ${DOMAINS}; do
  service="$(service_of "${domain}")"
  url="http://localhost:$(port_of "${domain}")"
  echo "==> [${domain}] Building/starting ${service} ..."
  if [[ "${REBUILD}" -eq 1 ]]; then
    compose "${domain}" build --no-cache "${service}"
  fi
  # --build rebuilds the image from the fresh jar (layer-cached) and recreates the container
  # only if the image changed.
  compose "${domain}" up -d --build "${service}"

  echo "==> [${domain}] Waiting for ${service} on ${url} ..."
  ready=0
  for _ in $(seq 1 60); do
    if curl -s -o /dev/null -X POST "${url}/engine/list" -H "Content-Type: application/json" -d '{}'; then
      ready=1
      break
    fi
    sleep 1
  done
  if [[ "${ready}" -ne 1 ]]; then
    echo "ERROR: ${service} did not come up in time. Last log lines:" >&2
    compose "${domain}" logs --tail 40 "${service}" >&2 || true
    exit 1
  fi
  echo "    -> ${service} is up on ${url} (logs: docker compose -f $(compose_of "${domain}") logs -f ${service})"
done

if [[ "${NO_FRONTEND}" -eq 1 ]]; then
  echo
  echo "Ready. Try each service's http/*.http files (publish_query.http, then try_query.http)."
  echo "Run ./stop.sh when you're done."
  exit 0
fi

# ---- 4. start the Query Builder UI (reuse the one this script started earlier) ----
tracked_pid="$(ui_tracked_pid)"
if [[ -n "${tracked_pid}" ]]; then
  echo "==> query-builder-ui is already running (pid ${tracked_pid}) -- reusing it."
else
  # Any dev server of this UI that isn't tracked (started by hand, or left over from a run that
  # lost its PID file) may be serving stale code on our port: stop it before starting ours.
  if [[ -n "$(ui_find_ours)" ]]; then
    echo "==> Found query-builder-ui dev server(s) not started by this script; stopping them first ..."
    ui_stop_all
  fi
  rm -f "${UI_PID_FILE}"

  # Something from another project holds the port: don't touch it, ask the user.
  foreign_pid="$(ui_foreign_listener)"
  if [[ -n "${foreign_pid}" ]]; then
    echo "ERROR: port ${UI_PORT} is in use by another program (pid ${foreign_pid}):" >&2
    ps -o pid=,command= -p "${foreign_pid}" >&2 || true
    echo "It isn't part of this project, so it wasn't stopped. Free the port and run ./dry-run.sh again." >&2
    exit 1
  fi

  # Reinstall when node_modules is missing or broken (an incomplete copy once lacked vite itself).
  if [[ ! -x "${UI_DIR}/node_modules/.bin/vite" ]] || ! "${UI_DIR}/node_modules/.bin/vite" --version >/dev/null 2>&1; then
    echo "==> Installing query-builder-ui dependencies (npm ci) ..."
    ( cd "${UI_DIR}" && npm ci --no-audit --no-fund )
  fi

  echo "==> Starting query-builder-ui (Vite dev server, own process group) ..."
  ui_start
  UI_STARTED_BY_THIS_RUN=1
  started_pid="$(cat "${UI_PID_FILE}")"

  echo "==> Waiting for query-builder-ui on ${FRONTEND_URL} ..."
  waited=0
  while ! curl -s -o /dev/null "${FRONTEND_URL}"; do
    if ! kill -0 "${started_pid}" 2>/dev/null; then
      echo "ERROR: query-builder-ui exited during startup. Last log lines:" >&2
      tail -n 40 "${UI_LOG}" >&2 || true
      exit 1
    fi
    if [[ "${waited}" -ge "${UI_READY_TIMEOUT_SECONDS}" ]]; then
      echo "ERROR: query-builder-ui did not come up within ${UI_READY_TIMEOUT_SECONDS}s. Last log lines:" >&2
      tail -n 40 "${UI_LOG}" >&2 || true
      exit 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "    -> query-builder-ui is up (pid ${started_pid}, process group ${started_pid}), log: ${UI_LOG}"
fi
UI_READY=1

# ---- 5. open the UI ----
if [[ "${NO_OPEN}" -eq 0 ]]; then
  echo "==> Opening ${FRONTEND_URL} ..."
  if command -v open >/dev/null 2>&1; then
    open "${FRONTEND_URL}"
  elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open "${FRONTEND_URL}"
  else
    echo "    (couldn't find a way to open a browser -- open it yourself)"
  fi
fi

echo
echo "Ready:"
printf '  - %-17s %s  (pick a domain in the header)\n' "query-builder-ui:" "${FRONTEND_URL}"
for domain in ${DOMAINS}; do
  printf '  - %-17s http://localhost:%s  (http files: %s/http/)\n' "$(service_of "${domain}"):" "$(port_of "${domain}")" "$(service_of "${domain}")"
done
echo
echo "Run ./stop.sh when you're done (stops the UI and every domain stack, resetting the databases)."
