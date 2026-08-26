package ru.privatenull.pnauth.hub

import ru.privatenull.pnauth.storage.JdbcAuthRepository
import ru.privatenull.pnauth.security.TotpKeyStore
import ru.privatenull.pnauth.security.TotpService
import java.nio.file.Path
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val dataFolder = Path.of(args.firstOrNull() ?: ".").toAbsolutePath().normalize()
    val config = HubConfig.load(dataFolder.resolve("hub.yml"))
    val repository = JdbcAuthRepository(config.databaseUrl, config.databaseUsername, config.databasePassword)
    val totp = TotpService(repository, TotpKeyStore.loadOrCreate(dataFolder.resolve("totp.key")))
    val server = HubHttpServer(config, HubCredentialService(repository, totp))
    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        repository.close()
    })
    server.start()
    println("pnAuth Hub запущен на ${config.host}:${config.port}")
    CountDownLatch(1).await()
}
