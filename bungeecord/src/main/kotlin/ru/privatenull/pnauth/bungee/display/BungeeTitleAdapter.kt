package ru.privatenull.pnauth.bungee.display

import net.md_5.bungee.api.ProxyServer
import ru.privatenull.pnauth.bungee.BungeeMessages
import ru.privatenull.pnauth.display.PlatformTitleAdapter
import ru.privatenull.pnauth.display.TitleBuilder
import ru.privatenull.pnauth.message.MessageFormat
import java.util.UUID

class BungeeTitleAdapter(
    private val proxy: ProxyServer,
    private val format: MessageFormat
) : PlatformTitleAdapter {

    override fun showTitle(uniqueId: UUID, builder: TitleBuilder) {
        val player = proxy.getPlayer(uniqueId) ?: return
        val displayTitle = if (builder.animationFrames.isNotEmpty()) builder.animationFrames[0] else builder.title

        val titleComp = BungeeMessages.component(displayTitle, format)
        val subtitleComp = BungeeMessages.component(builder.subtitle, format)

        val titleObj = proxy.createTitle()
            .title(titleComp)
            .subTitle(subtitleComp)
            .fadeIn(ticks(builder.fadeIn))
            .stay(ticks(builder.stay))
            .fadeOut(ticks(builder.fadeOut))

        player.sendTitle(titleObj)
    }

    override fun clearTitle(uniqueId: UUID) {
        val player = proxy.getPlayer(uniqueId) ?: return
        player.sendTitle(proxy.createTitle().clear())
    }

    private fun ticks(duration: java.time.Duration): Int {
        return Math.max(0, (duration.toMillis() / 50).toInt())
    }
}
