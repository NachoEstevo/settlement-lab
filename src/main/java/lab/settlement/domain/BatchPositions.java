package lab.settlement.domain;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact simultaneous positions. Individual debits can exceed the available balance. */
public record BatchPositions(Map<String, BigInteger> closingBalances, BigInteger grossAmount) {
    public BatchPositions {
        closingBalances = Collections.unmodifiableMap(new TreeMap<>(closingBalances));
    }

    /** Each endpoint must belong to the supplied account set. */
    public static BatchPositions calculate(Map<String, Long> balances, List<Payment> members) {
        var closing = new TreeMap<String, BigInteger>();
        balances.forEach((account, balance) -> closing.put(account, BigInteger.valueOf(balance)));
        var gross = BigInteger.ZERO;
        for (Payment member : members) {
            var amount = BigInteger.valueOf(member.amount());
            closing.put(member.from(), closing.get(member.from()).subtract(amount));
            closing.put(member.to(), closing.get(member.to()).add(amount));
            gross = gross.add(amount);
        }
        return new BatchPositions(closing, gross);
    }
}
