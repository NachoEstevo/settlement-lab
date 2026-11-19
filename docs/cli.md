# Command reference

Run `./gradlew -q run --args="COMMAND"` in the checkout, or pass the same arguments to the installed `settlement-lab` launcher. All examples use whole SIM units. The CLI accepts UUIDs in standard hyphenated form and normalizes them to lowercase. Money on input is an ASCII decimal integer with no leading zeroes. Each submitted payment amount is positive and fits a signed 64-bit integer.

| Command | Effect |
| --- | --- |
| `demo cycle [--race cancel-first\|settle-first]`, `demo unequal` | Start a fresh in-memory ledger and print the order, decisions, members and balances. |
| `demo selection` | Run two fixed queue fixtures and compare FIFO and heuristic volume with each hand-calculated optimum. |
| `pg migrate` | Apply or validate Flyway migrations against the configured database. |
| `pg init DOMAIN ACCOUNT=OPENING...` | Create a new domain and its opening balances in one transaction. |
| `pg submit DOMAIN COMMAND PAYMENT FROM TO AMOUNT` | Queue an immutable obligation. Distinct command IDs may yield `ALREADY_EXISTS` for the same terms or `PAYMENT_ID_CONFLICT` for different terms. |
| `pg cancel DOMAIN COMMAND PAYMENT` | Cancel a queued payment. Terminal outcomes include `CANCELLED`, `ALREADY_CANCELLED`, `ALREADY_SETTLED` with its batch, and `NOT_FOUND`. |
| `pg settle DOMAIN COMMAND BATCH REV [--policy fifo\|heuristic] PAYMENT...` | Submit 1–128 distinct members with their observed revision. Omitting the policy uses the original explicit multilateral contract. A confirmed batch returns `COMMITTED` with revision and exact gross. |
| `pg cycle DOMAIN fifo\|heuristic WINDOW [COMMAND BATCH]` | Inspect one window of 1–128 and attempt at most one batch. A nonempty selection prints the exact replay arguments before the write. An empty selection prints `NO_CANDIDATE` and leaves no receipt. |
| `pg receipt DOMAIN COMMAND`, `pg payment DOMAIN PAYMENT` | Read one receipt or payment from the primary. An absent receipt may still belong to an in-flight command. |
| `pg snapshot DOMAIN`, `pg export DOMAIN FILE`, `verify FILE` | Replay and print the live state, write a consistent standalone JSON history, or verify a file offline. The verifier stops at a contradiction and reports an error. |
| `pg race cancel-first\|settle-first [--drop-reply]` | Migrate, seed a fresh domain and force a real database lock wait. The optional fault withholds the winner's response after commit, then retries from a new session. |
| `benchmark economic [SEED [WINDOW]]` | Emit the inputs and results for both policies on three fixed-tick workloads. Defaults are 20261119 and 32. |
| `pg capacity [WORKERS PAYMENTS WINDOW SEED]` | Migrate and measure workers competing in one fresh domain. Defaults are 4 workers, 96 payments, a window of 8 and seed 42. |

Commands that reach a business decision exit 0 and print the outcome, including a financial `REJECTED` or identity conflict. Invalid arguments exit 2. Database, file and history verification errors exit nonzero. A transport error during commit leaves the result unknown. Retrieve the receipt or issue the original command again using identical IDs, revision, members and policy. Reusing a command ID with a different payload returns `COMMAND_ID_CONFLICT`. A freshly selected batch must use a new command ID.

The default database is the local Compose primary. `SETTLEMENT_JDBC_URL`, `SETTLEMENT_DB_USER` and `SETTLEMENT_DB_PASSWORD` select another lab database. `pg race` and `pg capacity` create their own fixtures. For other PostgreSQL commands run `pg migrate` before using a new database.
