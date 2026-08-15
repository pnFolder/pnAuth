package ru.privatenull.pnauth.velocity.dialog

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger

internal object VelocityDialogServiceFactory {

    @JvmStatic
    fun create(
        proxy: ProxyServer,
        logger: Logger,
        submissions: VelocityDialogService.SubmissionHandler
    ): VelocityDialogService {
        return try {
            val type = Class.forName(
                "ru.privatenull.pnauth.velocity.dialog.PacketEventsVelocityDialogService",
                true, VelocityDialogServiceFactory::class.java.classLoader
            )
            type.getConstructor(VelocityDialogService.SubmissionHandler::class.java)
                .newInstance(submissions) as VelocityDialogService
        } catch (exception: Throwable) {
            if (exception is ReflectiveOperationException || exception is LinkageError) {
                logger.warn("Bundled PacketEvents dialog integration failed; using commands.", exception)
                UnavailableDialogService.INSTANCE
            } else {
                throw exception
            }
        }
    }

    private enum class UnavailableDialogService : VelocityDialogService {
        INSTANCE;

        override fun available(): Boolean = false
        override fun show(player: Player, form: VelocityDialogService.DialogForm) {}
        override fun clear(player: Player) {}
    }
}
