# Settlement Lab decisions

## One writable authority per domain

**Accepted.** Every command writer takes the domain row lock in its own PostgreSQL `READ COMMITTED` transaction. Receipt lookup and the checks on payments and balances run in subsequent statements, after the writer acquires the lock. The decision, movements, membership and receipt commit together. The snapshot reader and selector run outside this transaction. [Transaction and recovery diagrams](architecture.md)

An overlapping proposal can become obsolete while a worker waits. A single mutex gives cancellation, settlement and replay a common ordering point and lets separate domains make progress independently. Locking each account individually would also need a consistent order across payments, queue changes, command identities and accounts. The chosen lock serializes writers in a busy domain. A failed primary pauses writes until storage and connectivity are restored.

## Bounded selection with explicit policy versions

**Accepted.** The reader gets the domain revision, a queue ordered by admission sequence, and participant balances in one read-only `REPEATABLE READ` transaction. It reads one extra payment to report whether more work exists. The public window is 1–128. Both selectors receive exactly the same snapshot.

| Stored policy | Selection and commit rules |
| --- | --- |
| `fifo-v1` | Select the longest prefix that can pay in sequence. The authority checks the current queue prefix under the lock, then replays transfers in admission order. Every intermediate sender and receiver balance must fit the account range. |
| `heuristic-v1` | Start with the window. Pick the lowest canonical UUID among deficit accounts, then remove its newest outgoing payment. If only upper-bound violations remain, pick the lowest overflowing account and remove its newest incoming payment. Repeat until feasible. Compare this group with the FIFO prefix by gross, then count, then oldest lexicographic sequence. The authority validates eligibility and simultaneous closing balances under the lock. |
| `multilateral-v1` | Raw proposals from the original CLI keep their historical payload and version. The authority checks the simultaneous close, and identical old commands still find their receipts. |

With A=100 and B=C=0, a worker on an old snapshot can propose A→B 100 followed by B→A 100. If another batch spends A's 100 first, FIFO must reject the old prefix even though its final net is zero. Conversely, an unrelated admission does not by itself invalidate an otherwise feasible proposal.

The heuristic eliminates at most one window of payments. Global optimization would have an exponential search space. The independent reference oracle enumerates all subsets up to 12 payments in tests. A's competing payments of 4 and 5 demonstrate the practical difference. With 5 available, the heuristic removes the newest and settles 4. The best subset settles 5. The gap of 1 is 20% of the optimum for that snapshot. The equal ring demonstrates the reverse comparison with FIFO. Both policies can leave old work pending behind a blocked head or outside the window. [Runnable examples](../README.md#select-a-batch)
