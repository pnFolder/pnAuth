package ru.privatenull.pnauth.kernel;
import ru.privatenull.pnauth.event.AuthEventBus;
import ru.privatenull.pnauth.extension.AuthExtensionRegistry;
import ru.privatenull.pnauth.display.PlayerDisplay;
import ru.privatenull.pnauth.kernel.service.ServiceRegistry;
import ru.privatenull.pnauth.platform.PnPlatform;
/** Generic extension surface. Consumers do not need to depend on authentication services. */
public interface ExtensionKernel {
    AuthEventBus events();
    AuthExtensionRegistry extensions();
    PlayerDisplay display();
    PnPlatform platform();
    ServiceRegistry services();
}
