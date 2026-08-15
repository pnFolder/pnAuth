package ru.privatenull.pnauth.paper;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import ru.privatenull.pnauth.display.ActionBarHandle;
import ru.privatenull.pnauth.display.ActionBarOptions;
import ru.privatenull.pnauth.display.BossBarColor;
import ru.privatenull.pnauth.display.BossBarHandle;
import ru.privatenull.pnauth.display.BossBarOptions;
import ru.privatenull.pnauth.display.BossBarOverlay;
import ru.privatenull.pnauth.display.DisplayHandle;
import ru.privatenull.pnauth.display.Easing;
import ru.privatenull.pnauth.display.PlayerDisplay;
import ru.privatenull.pnauth.display.TitleHandle;
import ru.privatenull.pnauth.display.TitleOptions;
import ru.privatenull.pnauth.platform.PlayerResourceKey;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Stateful Paper/Folia display implementation backed by Adventure audiences. */
public final class PaperPlayerDisplay implements PlayerDisplay, AutoCloseable {
    private final Plugin plugin;
    private final Map<PlayerResourceKey, Action> actionBars = new ConcurrentHashMap<>();
    private final Map<PlayerResourceKey, PlayerTitle> titles = new ConcurrentHashMap<>();
    private final Map<PlayerResourceKey, PlayerBossBar> bossBars = new ConcurrentHashMap<>();

    public PaperPlayerDisplay(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public ActionBarHandle actionBar(UUID playerId, String displayId, ActionBarOptions options) {
        PlayerResourceKey key = new PlayerResourceKey(playerId, displayId);
        return actionBars.compute(key, (ignored, current) -> {
            if (current == null || !current.active()) return new Action(key, options);
            current.apply(options);
            return current;
        });
    }

    @Override
    public TitleHandle title(UUID playerId, String displayId, TitleOptions options) {
        PlayerResourceKey key = new PlayerResourceKey(playerId, displayId);
        return titles.compute(key, (ignored, current) -> {
            if (current == null || !current.active()) return new PlayerTitle(key, options);
            current.apply(options);
            return current;
        });
    }

    @Override
    public BossBarHandle bossBar(UUID playerId, String displayId, BossBarOptions options) {
        PlayerResourceKey key = new PlayerResourceKey(playerId, displayId);
        return bossBars.compute(key, (ignored, current) -> {
            if (current == null || !current.active()) return new PlayerBossBar(key, options);
            current.apply(options);
            return current;
        });
    }

    @Override public Optional<ActionBarHandle> findActionBar(UUID id, String name) { return Optional.ofNullable(actionBars.get(new PlayerResourceKey(id, name))); }
    @Override public Optional<TitleHandle> findTitle(UUID id, String name) { return Optional.ofNullable(titles.get(new PlayerResourceKey(id, name))); }
    @Override public Optional<BossBarHandle> findBossBar(UUID id, String name) { return Optional.ofNullable(bossBars.get(new PlayerResourceKey(id, name))); }
    @Override public boolean removeActionBar(UUID id, String name) { return close(actionBars.remove(new PlayerResourceKey(id, name))); }
    @Override public boolean removeTitle(UUID id, String name) { return close(titles.remove(new PlayerResourceKey(id, name))); }
    @Override public boolean removeBossBar(UUID id, String name) { return close(bossBars.remove(new PlayerResourceKey(id, name))); }

    @Override
    public void clear(UUID playerId) {
        actionBars.entrySet().removeIf(entry -> closeIfPlayer(entry, playerId));
        titles.entrySet().removeIf(entry -> closeIfPlayer(entry, playerId));
        bossBars.entrySet().removeIf(entry -> closeIfPlayer(entry, playerId));
    }

    @Override
    public void close() {
        actionBars.values().forEach(DisplayHandle::close);
        titles.values().forEach(DisplayHandle::close);
        bossBars.values().forEach(DisplayHandle::close);
        actionBars.clear();
        titles.clear();
        bossBars.clear();
    }

    private abstract class Base implements DisplayHandle {
        protected final PlayerResourceKey key;
        protected volatile boolean active = true;
        protected volatile boolean paused;
        private volatile ScheduledTask expiry;

        protected Base(PlayerResourceKey key, Duration lifetime) {
            this.key = key;
            lifetime(lifetime);
        }
        @Override public UUID playerId() { return key.playerId(); }
        @Override public String displayId() { return key.resourceId(); }
        @Override public boolean active() { return active; }
        @Override public boolean paused() { return paused; }
        @Override public void pause() { paused = true; }
        @Override public void resume() { paused = false; }
        @Override
        public void lifetime(Duration lifetime) {
            cancel(expiry);
            if (valid(lifetime).isZero()) return;
            expiry = schedule(lifetime, this::close);
        }

        protected void withPlayer(java.util.function.Consumer<Player> action) {
            Player player = Bukkit.getPlayer(key.playerId());
            if (player == null) return;
            player.getScheduler().run(plugin, ignored -> action.accept(player), null);
        }

        protected ScheduledTask schedule(Duration delay, Runnable action) {
            Player player = Bukkit.getPlayer(key.playerId());
            if (player == null) return null;
            return player.getScheduler().runDelayed(
                    plugin, ignored -> action.run(), null, ticks(delay));
        }

        protected ScheduledTask repeat(Duration initialDelay, Duration interval, Runnable action) {
            Player player = Bukkit.getPlayer(key.playerId());
            if (player == null) return null;
            return player.getScheduler().runAtFixedRate(
                    plugin, ignored -> action.run(), null, ticks(initialDelay), ticks(interval));
        }

        protected void finish() {
            cancel(expiry);
        }
    }

    private final class Action extends Base implements ActionBarHandle {
        private volatile String text;
        private volatile ScheduledTask refresh;

        private Action(PlayerResourceKey key, ActionBarOptions options) { super(key, options.lifetime()); apply(options); }
        private void apply(ActionBarOptions options) {
            text(options.text());
            refreshInterval(options.refreshInterval());
            lifetime(options.lifetime());
        }
        @Override public void text(String text) { this.text = safe(text); sendNow(); }
        @Override public void refreshInterval(Duration interval) {
            cancel(refresh);
            if (!valid(interval).isZero()) refresh = repeat(interval, interval, this::sendNow);
        }
        @Override public void sendNow() { if (active && !paused) withPlayer(player -> player.sendActionBar(Component.text(text))); }
        @Override public void close() {
            if (!active) return;
            active = false;
            actionBars.remove(key, this);
            cancel(refresh);
            finish();
            withPlayer(player -> player.sendActionBar(Component.empty()));
        }
    }

    private final class PlayerTitle extends Base implements TitleHandle {
        private volatile String title = "";
        private volatile String subtitle = "";
        private volatile Duration fadeIn = Duration.ZERO;
        private volatile Duration stay = Duration.ofSeconds(2);
        private volatile Duration fadeOut = Duration.ZERO;
        private volatile ScheduledTask refresh;

        private PlayerTitle(PlayerResourceKey key, TitleOptions options) { super(key, options.lifetime()); apply(options); }
        private void apply(TitleOptions options) {
            title = safe(options.title()); subtitle = safe(options.subtitle());
            timings(options.fadeIn(), options.stay(), options.fadeOut());
            repeatInterval(options.repeatInterval()); lifetime(options.lifetime()); showNow();
        }
        @Override public void title(String value) { title = safe(value); showNow(); }
        @Override public void subtitle(String value) { subtitle = safe(value); showNow(); }
        @Override public void timings(Duration in, Duration visible, Duration out) { fadeIn = valid(in); stay = valid(visible); fadeOut = valid(out); }
        @Override public void repeatInterval(Duration interval) {
            cancel(refresh);
            if (!valid(interval).isZero()) refresh = repeat(interval, interval, this::showNow);
        }
        @Override public void showNow() {
            if (active && !paused) withPlayer(player -> player.showTitle(Title.title(
                    Component.text(title), Component.text(subtitle), Title.Times.times(fadeIn, stay, fadeOut))));
        }
        @Override public void clear() { withPlayer(Player::clearTitle); }
        @Override public void release() {
            if (!active) return;
            active = false;
            titles.remove(key, this);
            cancel(refresh);
            finish();
        }
        @Override public void close() { if (!active) return; release(); clear(); }
    }

    private final class PlayerBossBar extends Base implements BossBarHandle {
        private final BossBar bar;
        private volatile float progress;
        private volatile ScheduledTask animation;

        private PlayerBossBar(PlayerResourceKey key, BossBarOptions options) {
            super(key, options.lifetime());
            progress = clamp(options.progress());
            bar = BossBar.bossBar(Component.text(options.text()), progress,
                    adventureColor(options.color()), adventureOverlay(options.overlay()));
            properties(options.darkenScreen(), options.playBossMusic(), options.createWorldFog());
            lifetime(options.lifetime());
            withPlayer(player -> player.showBossBar(bar));
        }
        private void apply(BossBarOptions options) {
            text(options.text()); progress(options.progress()); color(options.color()); overlay(options.overlay());
            properties(options.darkenScreen(), options.playBossMusic(), options.createWorldFog());
        }
        @Override public void text(String text) { bar.name(Component.text(safe(text))); }
        @Override public void progress(float progress) { this.progress = clamp(progress); bar.progress(this.progress); }
        @Override public void color(BossBarColor color) { bar.color(adventureColor(color)); }
        @Override public void overlay(BossBarOverlay overlay) { bar.overlay(adventureOverlay(overlay)); }
        @Override public void animateProgress(float target, Duration duration, Easing easing) {
            cancel(animation);
            float start = progress;
            float end = clamp(target);
            int steps = Math.max(1, (int) (valid(duration).toMillis() / 50L));
            AtomicInteger step = new AtomicInteger();
            Easing curve = easing == null ? Easing.LINEAR : easing;
            animation = repeat(Duration.ofMillis(50), Duration.ofMillis(50), () -> {
                if (paused) return;
                float ratio = Math.min(1F, step.incrementAndGet() / (float) steps);
                progress(start + (end - start) * curve.apply(ratio));
                if (ratio >= 1F) cancel(animation);
            });
        }
        @Override public void properties(boolean dark, boolean music, boolean fog) {
            java.util.Set<BossBar.Flag> flags = new java.util.HashSet<>();
            if (dark) flags.add(BossBar.Flag.DARKEN_SCREEN);
            if (music) flags.add(BossBar.Flag.PLAY_BOSS_MUSIC);
            if (fog) flags.add(BossBar.Flag.CREATE_WORLD_FOG);
            bar.flags(flags);
        }
        @Override public void pause() { super.pause(); withPlayer(player -> player.hideBossBar(bar)); }
        @Override public void resume() { super.resume(); withPlayer(player -> player.showBossBar(bar)); }
        @Override public void close() {
            if (!active) return;
            active = false;
            bossBars.remove(key, this);
            cancel(animation);
            finish();
            withPlayer(player -> player.hideBossBar(bar));
        }
    }

    private static boolean close(DisplayHandle handle) { if (handle == null) return false; handle.close(); return true; }
    private static boolean closeIfPlayer(Map.Entry<PlayerResourceKey, ? extends DisplayHandle> entry, UUID playerId) {
        if (!entry.getKey().playerId().equals(playerId)) return false;
        entry.getValue().close(); return true;
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static Duration valid(Duration value) { return value == null || value.isNegative() ? Duration.ZERO : value; }
    private static long ticks(Duration value) { return Math.max(1L, (valid(value).toMillis() + 49L) / 50L); }
    private static void cancel(ScheduledTask task) { if (task != null) task.cancel(); }
    private static float clamp(float value) { return Math.max(0F, Math.min(1F, value)); }
    private static BossBar.Color adventureColor(BossBarColor value) { return BossBar.Color.valueOf((value == null ? BossBarColor.PURPLE : value).name()); }
    private static BossBar.Overlay adventureOverlay(BossBarOverlay value) { return BossBar.Overlay.valueOf((value == null ? BossBarOverlay.PROGRESS : value).name()); }

}
