# Contributing to Settlement Lab

Use JDK 21 and the Gradle Wrapper from the checkout. The reference cycle and unit suite run locally.

```sh
./gradlew test
./gradlew -q run --args="demo cycle"
```

For changes to SQL, receipts or transaction ordering, run the integration suite on a local lab database. In PowerShell the wrapper is `.\gradlew.bat`.

```sh
docker compose up -d --wait
./gradlew integrationTest
./gradlew -q run --args="pg race settle-first --drop-reply"
```

The race prints its domain and command IDs. Use them with `pg receipt` and `pg export` to investigate the committed result. The `verify` command checks an export offline. On Linux, `bash scripts/test-postgres.sh` also tests recovery across a database restart in its own Compose project.

For a settlement change, include the opening balances, ordered payments and expected batch members in the regression. A net-zero cycle can leave every balance unchanged even when a payment is missing from the batch. For a concurrency change, identify which transaction held the lock, which one waited and what each client learned. The architecture guide explains the authority and its receipts. [Architecture](docs/architecture.md)
