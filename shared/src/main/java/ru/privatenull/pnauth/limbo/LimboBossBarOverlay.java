package ru.privatenull.pnauth.limbo;

public enum LimboBossBarOverlay {
    PROGRESS(0), NOTCHED_6(1), NOTCHED_10(2), NOTCHED_12(3), NOTCHED_20(4);

    private final int id;

    LimboBossBarOverlay(int id) {
        this.id = id;
    }

    int id() {
        return id;
    }
}
