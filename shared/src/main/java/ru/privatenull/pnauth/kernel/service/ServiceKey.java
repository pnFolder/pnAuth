package ru.privatenull.pnauth.kernel.service;
public record ServiceKey<T>(String namespace, String name, Class<T> type) {
    public ServiceKey {
        if (namespace == null || !namespace.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("invalid namespace");
        if (name == null || !name.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("invalid service name");
        java.util.Objects.requireNonNull(type, "type");
    }
    public static <T> ServiceKey<T> of(String namespace, String name, Class<T> type) {
        return new ServiceKey<>(namespace, name, type);
    }
    public String id() { return namespace + ":" + name; }
}
