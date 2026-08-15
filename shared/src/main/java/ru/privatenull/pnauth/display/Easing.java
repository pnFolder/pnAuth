package ru.privatenull.pnauth.display;
public enum Easing {
    LINEAR { public float apply(float t) { return t; } },
    EASE_IN { public float apply(float t) { return t * t; } },
    EASE_OUT { public float apply(float t) { return 1F - (1F - t) * (1F - t); } },
    EASE_IN_OUT { public float apply(float t) { return t < .5F ? 2F * t * t : 1F - (float) Math.pow(-2F * t + 2F, 2) / 2F; } };
    public abstract float apply(float progress);
}
