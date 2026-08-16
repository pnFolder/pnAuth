package ru.privatenull.pnauth.dev

/**
 * Marks experimental development features intended for testing and debugging.
 * Easy to locate, toggle, or remove when development finishes.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class DevExperimental(val description: String = "")

/**
 * Global development flags.
 * Set [STAY_ON_AUTH_SERVER] to true to prevent automatic redirection to the backend server
 * after successful login/registration so you can remain on the auth server to test UI and titles.
 */
@DevExperimental("Development mode flags to assist local testing without connecting to backend server")
object DevFlags {
    /**
     * When set to true, authenticated players remain on the authentication server / limbo
     * instead of being connected/routed to the backend server.
     * Set to false or toggle off when done testing.
     */
    @JvmField
    var STAY_ON_AUTH_SERVER: Boolean = false
}
