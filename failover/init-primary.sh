#!/usr/bin/env bash
# The image runs initialization against its temporary Unix-socket server.
(
  set -eu
  psql -X -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -c "CREATE ROLE settlement_replica WITH LOGIN REPLICATION PASSWORD 'local_replica'"
  echo 'host replication settlement_replica all scram-sha-256' >> "$PGDATA/pg_hba.conf"
)
