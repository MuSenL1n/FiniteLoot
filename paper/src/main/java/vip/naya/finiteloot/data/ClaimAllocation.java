package vip.naya.finiteloot.data;

public record ClaimAllocation(Kind kind, byte[] contents) {
    public enum Kind {
        NEW,
        EXISTING,
        COMPLETED,
        EXHAUSTED,
        PENDING,
        NOT_FOUND
    }

    public static ClaimAllocation of(Kind kind) {
        return new ClaimAllocation(kind, null);
    }
}

