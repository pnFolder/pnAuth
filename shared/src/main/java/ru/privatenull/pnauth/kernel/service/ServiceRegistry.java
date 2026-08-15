package ru.privatenull.pnauth.kernel.service;
import java.util.List;
import java.util.Optional;
public interface ServiceRegistry {
    <T> ServiceRegistration register(ServiceKey<T> key, String ownerId, int priority, T service);
    <T> Optional<T> find(ServiceKey<T> key);
    <T> List<T> findAll(ServiceKey<T> key);
}
