package lab.settlement.benchmark;

import java.sql.Connection;
import lab.settlement.postgres.PgAuthority;

/** Thread-confined marks around the measured JDBC statements. */
final class CapacityTimings implements PgAuthority.Hooks {
    private long beforeLock;
    private long afterLock;
    private long beforeCommit;
    private long afterCommit;

    @Override public void beforeLock(Connection c) { beforeLock = System.nanoTime(); }
    @Override public void afterLock(Connection c) { afterLock = System.nanoTime(); }
    @Override public void beforeCommit(Connection c) { beforeCommit = System.nanoTime(); }
    @Override public void afterCommit(Connection c) { afterCommit = System.nanoTime(); }

    long lock() { return afterLock - beforeLock; }
    long critical() { return beforeCommit - afterLock; }
    long commit() { return afterCommit - beforeCommit; }
}
