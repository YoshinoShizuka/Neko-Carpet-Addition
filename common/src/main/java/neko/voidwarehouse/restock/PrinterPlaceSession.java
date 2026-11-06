package neko.voidwarehouse.restock;

import java.util.LinkedHashMap;
import java.util.Map;

/** One live connection's bounded replay cache and monotonic-time rate limits. */
public final class PrinterPlaceSession {
    private static final int CAPACITY = 64;
    private static final int MAX_RESULTS = 256;
    private static final double TOKENS_PER_NANOSECOND = 640.0 / 1_000_000_000.0;
    private final Map<Integer, Integer> results = new LinkedHashMap<>();
    private int highestNonce;
    private double tokens = CAPACITY;
    private long refillAt;
    private long helloAt = Long.MIN_VALUE;
    private long correctionAt = Long.MIN_VALUE;
    private int currentTick = Integer.MIN_VALUE;
    private int workThisTick;
    private boolean negotiated;

    /** -1 admits one execution; all other values are final wire statuses, including cached replies. */
    public int begin(int nonce, long now, int serverTick) {
        if (nonce <= 0) {
            return 5;
        }
        Integer known = results.get(nonce);
        if (known != null) {
            return known;
        }
        if (nonce <= highestNonce) {
            return 6;
        }
        highestNonce = nonce;
        if (currentTick != serverTick) {
            currentTick = serverTick;
            workThisTick = 0;
        }
        if (workThisTick >= CAPACITY) {
            complete(nonce, 4);
            return 4;
        }
        if (refillAt == 0) {
            refillAt = now;
        }
        long elapsed = Math.max(0L, now - refillAt);
        tokens = Math.min(CAPACITY, tokens + elapsed * TOKENS_PER_NANOSECOND);
        refillAt = Math.max(refillAt, now);
        if (tokens < 1.0) {
            complete(nonce, 4);
            return 4;
        }
        tokens -= 1.0;
        workThisTick++;
        return -1;
    }

    public void complete(int nonce, int status) {
        results.put(nonce, status);
        if (results.size() > MAX_RESULTS) {
            results.remove(results.keySet().iterator().next());
        }
    }

    public boolean allowHello(long now) {
        if (helloAt != Long.MIN_VALUE && now - helloAt < 500_000_000L) {
            return false;
        }
        helloAt = now;
        negotiated = true;
        return true;
    }

    public boolean negotiated() {
        return negotiated;
    }

    /** Rejected floods must not turn into unbounded block-update replies. */
    public boolean allowCorrection(long now) {
        if (correctionAt != Long.MIN_VALUE && now - correctionAt < 50_000_000L) {
            return false;
        }
        correctionAt = now;
        return true;
    }

    int retainedResults() {
        return results.size();
    }
}
