package ru.privatenull.pnauth.velocity.display

import com.velocitypowered.api.proxy.ProxyServer
import net.kyori.adventure.title.Title
import ru.privatenull.pnauth.display.PlatformTitleAdapter
import ru.privatenull.pnauth.display.TitleBuilder
import ru.privatenull.pnauth.message.MessageFormat
import ru.privatenull.pnauth.velocity.VelocityMessages
import java.util.UUID

class VelocityTitleAdapter(
    private val server: ProxyServer,
    private val format: MessageFormat
) : PlatformTitleAdapter {

    override fun showTitle(uniqueId: UUID, builder: TitleBuilder) {
        val player = server.getPlayer(uniqueId).orElse(null) ?: return
        val displayTitle = if (builder.animationFrames.isNotEmpty()) builder.animationFrames[0] else builder.title

        val titleComp = VelocityMessages.component(displayTitle, format)
        val subtitleComp = VelocityMessages.component(builder.subtitle, format)

        val titleObj = Title.title(
            titleComp,
            subtitleComp,
            Title.Times.times(builder.fadeIn, builder.stay, builder.fadeOut)
        )

        player.showTitle(titleObj)
    }

    override fun clearTitle(uniqueId: UUID) {
        val player = server.getPlayer(uniqueId).orElse(null) ?: return
        player.clearTitle()
    }
}
