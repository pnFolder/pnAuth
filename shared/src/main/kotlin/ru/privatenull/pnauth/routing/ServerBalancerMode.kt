package ru.privatenull.pnauth.routing

enum class ServerBalancerMode {
    LEAST_PLAYERS,
    LOWEST_LOAD_PERCENT,
    FIRST_AVAILABLE,
    ROUND_ROBIN,
    RANDOM,
    FILLING
}
