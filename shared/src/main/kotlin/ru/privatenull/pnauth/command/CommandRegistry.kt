package ru.privatenull.pnauth.command

import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Shared registry and dispatcher for public and internal pnAuth commands. */
class CommandRegistry : CommandService {
    private val routes = LinkedHashMap<String, Route>()
    private val registrations = ArrayList<Route>()

    @Synchronized
    fun register(service: CommandService): AutoCloseable {
        val route = Route(service, service.definitions())
        for (definition in route.definitions) {
            requireAvailable(definition.name)
            definition.aliases.forEach(this::requireAvailable)
        }
        registrations.add(route)
        for (definition in route.definitions) {
            routes[normalize(definition.name)] = route
            definition.aliases.forEach { alias -> routes[normalize(alias)] = route }
        }
        return AutoCloseable { unregister(route) }
    }

    @Synchronized
    override fun definitions(): List<CommandSpec> {
        return registrations.flatMap { it.definitions }
    }

    override fun execute(context: CommandContext): CompletionStage<List<String>> {
        val service = service(context.command)
        return service?.execute(context) ?: CompletableFuture.completedFuture(emptyList())
    }

    override fun suggest(context: CommandContext): List<String> {
        val service = service(context.command)
        return service?.suggest(context) ?: emptyList()
    }

    @Synchronized
    private fun service(root: String): CommandService? {
        val route = routes[normalize(root)]
        return route?.service
    }

    @Synchronized
    private fun unregister(route: Route) {
        if (!registrations.remove(route)) return
        routes.entries.removeIf { it.value == route }
    }

    private fun requireAvailable(root: String) {
        require(!routes.containsKey(normalize(root))) { "Duplicate command root: $root" }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)

    private class Route(val service: CommandService, val definitions: List<CommandSpec>)
}
