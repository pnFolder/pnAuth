package ru.privatenull.pnauth.kernel.service;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
public final class DefaultServiceRegistry implements ServiceRegistry {
    private final ConcurrentHashMap<ServiceKey<?>, CopyOnWriteArrayList<Entry<?>>> services = new ConcurrentHashMap<>();
    @Override public <T> ServiceRegistration register(ServiceKey<T> key, String ownerId, int priority, T service) {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        if (priority < -5_000 || priority > 5_000) throw new IllegalArgumentException("priority must be between -5000 and 5000");
        if (!key.type().isInstance(service)) throw new IllegalArgumentException("service does not implement " + key.type().getName());
        var entries = services.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        Entry<T> entry = new Entry<>(ownerId, priority, service); entries.add(entry);
        entries.sort(Comparator.comparingInt((Entry<?> value) -> value.priority).reversed());
        return () -> { entries.remove(entry); if (entries.isEmpty()) services.remove(key, entries); };
    }
    @Override public <T> Optional<T> find(ServiceKey<T> key) { return findAll(key).stream().findFirst(); }
    @Override public <T> List<T> findAll(ServiceKey<T> key) {
        List<T> result = new ArrayList<>();
        for (Entry<?> entry : services.getOrDefault(key, new CopyOnWriteArrayList<>())) result.add(key.type().cast(entry.service));
        return List.copyOf(result);
    }
    private record Entry<T>(String ownerId, int priority, T service) { }
}
