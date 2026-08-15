package ru.privatenull.pnauth.limbo;

public enum LimboBossBarColor {
    PINK(0), BLUE(1), RED(2), GREEN(3), YELLOW(4), PURPLE(5), WHITE(6);

    private final int id;

    LimboBossBarColor(int id) {
        this.id = id;
    }

    int id() {
        return id;
    }
}
