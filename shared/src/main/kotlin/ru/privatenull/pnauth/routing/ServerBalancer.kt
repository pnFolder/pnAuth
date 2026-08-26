package ru.privatenull.pnauth.routing

import ru.privatenull.pnauth.platform.Proxy
import java.util.Optional

/** Strategy for selecting the target server among multiple candidates. */
interface ServerBalancer {
    fun selectServer(targets: List<String>, proxy: Proxy?): Optional<String>
}
