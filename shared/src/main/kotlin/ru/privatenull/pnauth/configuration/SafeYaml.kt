package ru.privatenull.pnauth.configuration

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Centralized safe SnakeYAML configuration.
 *
 * We only need plain YAML primitives for configuration/messages:
 * Maps, Lists, Strings, Numbers, Booleans and nulls.
 *
 * Using SnakeYAML default constructors may allow instantiating arbitrary Java types
 * via YAML tags, which is unsafe when files can be modified by third parties.
 */
object SafeYaml {
    @JvmStatic
    fun create(): Yaml {
        val options = LoaderOptions().apply {
            // harden parsing to avoid "billion laughs" style memory abuse
            maxAliasesForCollections = 50
            codePointLimit = 2_000_000
        }
        // SnakeYAML 2.x exposes this via a setter, not a Kotlin property.
        options.setAllowDuplicateKeys(false)
        return Yaml(SafeConstructor(options))
    }
}
