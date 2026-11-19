#!/usr/bin/env bash
set -euo pipefail
install -d -m 0700 -o postgres -g postgres "$PGDATA"
if [[ ! -f "$PGDATA/PG_VERSION" ]]; then
  # -R installs standby.signal and records this connection for the WAL receiver.
  gosu postgres pg_basebackup --pgdata="$PGDATA" --write-recovery-conf \
    --wal-method=stream --checkpoint=fast --no-password \
    --dbname='host=primary user=settlement_replica password=local_replica application_name=settlement_standby connect_timeout=3'
fi
exec docker-entrypoint.sh postgres
