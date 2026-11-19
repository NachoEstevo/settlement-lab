#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
scratch=$(mktemp -d "${TMPDIR:-/tmp}/settlement-test-XXXXXXXXXXXX")
project=${scratch##*/}
project=${project,,}
compose=(docker compose -p "$project" -f compose.yaml)
export SETTLEMENT_PORT=0 SETTLEMENT_DB_USER=settlement_lab SETTLEMENT_DB_PASSWORD=settlement_lab

# An explicit subnet lets a shared Docker host run this check after its default
# address pool is exhausted. The network still belongs to this temporary project.
if [[ -n ${SETTLEMENT_TEST_SUBNET:-} ]]; then
    [[ $SETTLEMENT_TEST_SUBNET =~ ^[0-9.]+/[0-9]+$ ]]
    printf 'networks:\n  default:\n    ipam:\n      config:\n        - subnet: "%s"\n' \
        "$SETTLEMENT_TEST_SUBNET" > "$scratch/network.yaml"
    compose+=(-f "$scratch/network.yaml")
fi

cleanup() {
    local status=$?
    if (( status != 0 )); then
        "${compose[@]}" logs --no-color postgres >&2 || true
    fi
    "${compose[@]}" down --volumes || status=1
    rm -f -- "$scratch"/*
    rmdir -- "$scratch"
    exit "$status"
}
trap cleanup EXIT

connect_to_postgres() {
    local address
    "${compose[@]}" up -d --wait --wait-timeout 120 postgres
    address=$("${compose[@]}" port postgres 5432)
    export SETTLEMENT_JDBC_URL="jdbc:postgresql://${address%$'\r'}/settlement_lab"
}
connect_to_postgres
./gradlew -q run --args="pg migrate"
./gradlew --no-daemon --console=plain integrationTest installDist
cli=build/install/settlement-lab/bin/settlement-lab
"$cli" pg migrate

# Parse the documented key=value lines, failing on missing or ambiguous output.
field() {
    awk -v key="$1=" 'index($0, key) == 1 { count++; sub(/\r$/, ""); print substr($0, length(key) + 1) } END { if (count != 1) exit 1 }' "$2"
}

record_race() {
    local order=$1 expected=$2 recovered=$3
    shift 3
    local log="$scratch/$order.log"
    "$cli" pg race "$order" "$@" | tee "$log"
    [[ $(field wait_event_type "$log") == Lock ]]
    local holder waiter
    holder=$(field holder_pid "$log")
    waiter=$(field waiter_pid "$log")
    [[ $holder =~ ^[1-9][0-9]*$ && $waiter =~ ^[1-9][0-9]*$ && $holder != "$waiter" ]]
    [[ $(field reply_recovered "$log") == "$recovered" ]]

    local domain command
    domain=$(field domain "$log")
    command=$(field settle_command "$log")
    "$cli" pg receipt "$domain" "$command" | tee "$scratch/$order.receipt"
    grep -Eq "^outcome=$expected([[:space:]]|$)" "$scratch/$order.receipt"
    "$cli" pg export "$domain" "$scratch/$order.before.json"
    "$cli" verify "$scratch/$order.before.json"
}

record_race cancel-first REJECTED false
record_race settle-first COMMITTED true --drop-reply

postgres_identity() {
    "${compose[@]}" exec -T postgres psql -XAtq -v ON_ERROR_STOP=1 \
        -U settlement_lab -d settlement_lab -c 'SELECT system_identifier, pg_postmaster_start_time() FROM pg_control_system()'
}
before=$(postgres_identity)
"${compose[@]}" restart postgres
connect_to_postgres
after=$(postgres_identity)
[[ $before == *"|"* && $after == *"|"* && $before != "$after" && ${before%%|*} == "${after%%|*}" ]]
printf 'PostgreSQL restarted on the same cluster: %s -> %s\n' "$before" "$after"

check_replay() {
    local order=$1 log="$scratch/$1.log"
    local domain command batch revision ids
    domain=$(field domain "$log")
    command=$(field settle_command "$log")
    batch=$(field batch "$log")
    revision=$(field snapshot_revision "$log")
    ids=$(field members "$log")
    local -a members
    read -r -a members <<< "$ids"
    [[ ${#members[@]} == 3 ]]

    "$cli" pg receipt "$domain" "$command" > "$scratch/$order.recovered"
    cmp "$scratch/$order.receipt" "$scratch/$order.recovered"
    "$cli" pg export "$domain" "$scratch/$order.restart.json"
    cmp "$scratch/$order.before.json" "$scratch/$order.restart.json"
    "$cli" pg settle "$domain" "$command" "$batch" "$revision" "${members[@]}" > "$scratch/$order.replayed"
    cmp "$scratch/$order.receipt" "$scratch/$order.replayed"
    "$cli" pg export "$domain" "$scratch/$order.replay.json"
    cmp "$scratch/$order.before.json" "$scratch/$order.replay.json"
    "$cli" verify "$scratch/$order.replay.json"
}

check_replay cancel-first
check_replay settle-first
printf 'Both race histories and receipts survived restart and replay.\n'

# Start two packaged clients with one command identity. The receipt and history
# must describe one admission even when both processes request it.
domain=00000000-0000-0000-0000-000000000001
from=00000000-0000-0000-0000-000000000002
to=00000000-0000-0000-0000-000000000003
command=00000000-0000-0000-0000-000000000004
payment=00000000-0000-0000-0000-000000000005
"$cli" pg init "$domain" "$from=20" "$to=0"
submit=("$cli" pg submit "$domain" "$command" "$payment" "$from" "$to" 10)
"${submit[@]}" > "$scratch/client1" &
first=$!
"${submit[@]}" > "$scratch/client2" &
second=$!
status=0
wait "$first" || status=1
wait "$second" || status=1
(( status == 0 ))
cmp "$scratch/client1" "$scratch/client2"
grep -Eq '^outcome=QUEUED revision=1 ' "$scratch/client1"
"$cli" pg export "$domain" "$scratch/clients.json"
"$cli" verify "$scratch/clients.json" | tee "$scratch/clients.verified"
grep -Eq '^VERIFIED revision=1 payments=1 settled=0 cancelled=0 batches=0 gross=0 balance=20[[:space:]]*$' \
    "$scratch/clients.verified"
