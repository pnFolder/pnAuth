package ru.privatenull.pnauth.config

/** Разрешает секреты только из явной конструкции ${'$'}{ENV:NAME}; произвольные подстановки запрещены. */
object SecretResolver {
    private val pattern = Regex("^\\$\\{ENV:([A-Z][A-Z0-9_]*)}$")

    @JvmStatic
    fun resolve(value: String?, environment: Map<String, String> = System.getenv()): String {
        val source = value.orEmpty().trim()
        val match = pattern.matchEntire(source) ?: return source
        val name = match.groupValues[1]
        return environment[name] ?: throw IllegalArgumentException("Environment variable $name is not set")
    }
}
