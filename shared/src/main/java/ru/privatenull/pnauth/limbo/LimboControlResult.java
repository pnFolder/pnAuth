package ru.privatenull.pnauth.limbo;

public enum LimboControlResult {
    ACCEPTED(0),
    INVALID_ARGUMENT(1),
    PLAYER_NOT_FOUND(2),
    QUEUE_FULL(3),
    QUEUE_CLOSED(4);

    private final int code;

    LimboControlResult(int code) {
        this.code = code;
    }

    static LimboControlResult fromCode(int code) {
        for (LimboControlResult result : values()) {
            if (result.code == code) return result;
        }
        throw new IllegalStateException("Unknown PicoLimbo control result: " + code);
    }
}
