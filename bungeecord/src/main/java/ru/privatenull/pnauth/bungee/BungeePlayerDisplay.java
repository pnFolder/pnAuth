package ru.privatenull.pnauth.bungee;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.protocol.packet.BossBar;
import ru.privatenull.pnauth.display.*;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.platform.PlayerResourceKey;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

final class BungeePlayerDisplay implements PlayerDisplay, AutoCloseable {
    private final ProxyServer proxy;
    private final MessageFormat format;
    private final ConcurrentMap<PlayerResourceKey,Action> actions=new ConcurrentHashMap<>();private final ConcurrentMap<PlayerResourceKey,Titles> titles=new ConcurrentHashMap<>();private final ConcurrentMap<PlayerResourceKey,Boss> bosses=new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "pnauth-bungee-display"); thread.setDaemon(true); return thread;
    });
    BungeePlayerDisplay(ProxyServer proxy, MessageFormat format) { this.proxy = proxy; this.format = format; }
    public ActionBarHandle actionBar(UUID id,String name,ActionBarOptions o){PlayerResourceKey k=key(id,name);return actions.compute(k,(x,v)->{if(v==null||!v.active)return new Action(k,o);v.apply(o);return v;});}
    public TitleHandle title(UUID id,String name,TitleOptions o){PlayerResourceKey k=key(id,name);return titles.compute(k,(x,v)->{if(v==null||!v.active)return new Titles(k,o);v.apply(o);return v;});}
    public BossBarHandle bossBar(UUID id,String name,BossBarOptions o){PlayerResourceKey k=key(id,name);return bosses.compute(k,(x,v)->{if(v==null||!v.active)return new Boss(k,o);v.apply(o);return v;});}
    public java.util.Optional<ActionBarHandle> findActionBar(UUID id,String name){return java.util.Optional.ofNullable(actions.get(key(id,name)));}public java.util.Optional<TitleHandle> findTitle(UUID id,String name){return java.util.Optional.ofNullable(titles.get(key(id,name)));}public java.util.Optional<BossBarHandle> findBossBar(UUID id,String name){return java.util.Optional.ofNullable(bosses.get(key(id,name)));}
    public boolean removeActionBar(UUID id,String name){Action h=actions.remove(key(id,name));if(h!=null)h.close();return h!=null;}public boolean removeTitle(UUID id,String name){Titles h=titles.remove(key(id,name));if(h!=null)h.close();return h!=null;}public boolean removeBossBar(UUID id,String name){Boss h=bosses.remove(key(id,name));if(h!=null)h.close();return h!=null;}
    public void clear(UUID id){actions.entrySet().stream().filter(e->e.getKey().playerId.equals(id)).map(java.util.Map.Entry::getValue).toList().forEach(Action::close);titles.entrySet().stream().filter(e->e.getKey().playerId.equals(id)).map(java.util.Map.Entry::getValue).toList().forEach(Titles::close);bosses.entrySet().stream().filter(e->e.getKey().playerId.equals(id)).map(java.util.Map.Entry::getValue).toList().forEach(Boss::close);}
    @Override public void close() { actions.values().forEach(Action::close);titles.values().forEach(Titles::close);bosses.values().forEach(Boss::close);scheduler.shutdownNow(); }

    private abstract class Base implements DisplayHandle {
        final PlayerResourceKey key; final UUID playerId; volatile boolean active = true; volatile boolean paused; volatile ScheduledFuture<?> expiry;
        Base(PlayerResourceKey key, Duration lifetime) { this.key=key;playerId = key.playerId; lifetime(lifetime); }
        public UUID playerId() { return playerId; } public String displayId(){return key.name;} public boolean active() { return active; } public boolean paused() { return paused; }
        public void pause() { paused = true; } public void resume() { if (active) paused = false; }
        public synchronized void lifetime(Duration value) { if (expiry != null) expiry.cancel(false); if (value != null && !value.isZero() && !value.isNegative()) expiry = scheduler.schedule(this::close, value.toMillis(), TimeUnit.MILLISECONDS); }
        ProxiedPlayer player() { return proxy.getPlayer(playerId); }
        void cancel(ScheduledFuture<?> task) { if (task != null) task.cancel(false); }
    }

    private final class Action extends Base implements ActionBarHandle {
        volatile String text; volatile Duration interval; volatile ScheduledFuture<?> refresh;
        Action(PlayerResourceKey key, ActionBarOptions options) { super(key, options.lifetime()); text = options.text(); interval = options.refreshInterval(); sendNow(); reschedule(); }
        void apply(ActionBarOptions o){text(o.text());refreshInterval(o.refreshInterval());lifetime(o.lifetime());}
        public void text(String value) { text = value == null ? "" : value; sendNow(); }
        public synchronized void refreshInterval(Duration value) { interval = valid(value); reschedule(); }
        private synchronized void reschedule() { cancel(refresh); if (active && !interval.isZero()) refresh = scheduler.scheduleAtFixedRate(this::sendNow, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS); }
        public void sendNow() { ProxiedPlayer player = player(); if (active && !paused && player != null) player.sendMessage(ChatMessageType.ACTION_BAR, BungeeMessages.component(text, format)); }
        public synchronized void close() { if (!active) return; active = false;actions.remove(key,this); cancel(refresh); cancel(expiry); ProxiedPlayer player = player(); if (player != null) player.sendMessage(ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent("")); }
    }

    private final class Titles extends Base implements TitleHandle {
        volatile String title, subtitle; volatile Duration fadeIn, stay, fadeOut, repeat; volatile ScheduledFuture<?> refresh;
        Titles(PlayerResourceKey key, TitleOptions options) { super(key, options.lifetime()); title=options.title(); subtitle=options.subtitle(); fadeIn=options.fadeIn(); stay=options.stay(); fadeOut=options.fadeOut(); repeat=options.repeatInterval(); showNow(); reschedule(); }
        void apply(TitleOptions o){title=o.title();subtitle=o.subtitle();timings(o.fadeIn(),o.stay(),o.fadeOut());repeatInterval(o.repeatInterval());lifetime(o.lifetime());showNow();}
        public synchronized void title(String value) { title=value == null ? "" : value; showNow(); } public synchronized void subtitle(String value) { subtitle=value == null ? "" : value; showNow(); }
        public synchronized void timings(Duration a, Duration b, Duration c) { fadeIn=valid(a); stay=valid(b); fadeOut=valid(c); }
        public synchronized void repeatInterval(Duration value) { repeat=valid(value); reschedule(); }
        private synchronized void reschedule() { cancel(refresh); if (active && !repeat.isZero()) refresh=scheduler.scheduleAtFixedRate(this::showNow, repeat.toMillis(), repeat.toMillis(), TimeUnit.MILLISECONDS); }
        public synchronized void showNow() { ProxiedPlayer player=player(); if(active&&!paused&&player!=null) player.sendTitle(proxy.createTitle().title(BungeeMessages.component(title,format)).subTitle(BungeeMessages.component(subtitle,format)).fadeIn(ticks(fadeIn)).stay(ticks(stay)).fadeOut(ticks(fadeOut))); }
        public void clear() { ProxiedPlayer player=player(); if(player!=null) player.sendTitle(proxy.createTitle().clear()); }
        public synchronized void release() { if(!active)return; active=false;titles.remove(key,this); cancel(refresh); cancel(expiry); }
        public synchronized void close() { if(!active)return; release(); clear(); }
    }

    private final class Boss extends Base implements BossBarHandle {
        final UUID barId; volatile String text; volatile float progress; volatile BossBarColor color; volatile BossBarOverlay overlay;
        volatile byte flags; volatile ScheduledFuture<?> animation;
        Boss(PlayerResourceKey key, BossBarOptions options) { super(key,options.lifetime());barId=UUID.nameUUIDFromBytes((key.playerId+":"+key.name).getBytes(java.nio.charset.StandardCharsets.UTF_8)); text=options.text();progress=options.progress();color=options.color();overlay=options.overlay(); flags=(byte)((options.darkenScreen()?1:0)|(options.playBossMusic()?2:0)|(options.createWorldFog()?4:0)); packet(0); }
        void apply(BossBarOptions o){text(o.text());progress(o.progress());color(o.color());overlay(o.overlay());properties(o.darkenScreen(),o.playBossMusic(),o.createWorldFog());lifetime(o.lifetime());}
        public void text(String value){text=value==null?"":value;packet(3);} public void progress(float value){progress=clamp(value);packet(2);}
        public void color(BossBarColor value){color=value==null?BossBarColor.PURPLE:value;packet(4);} public void overlay(BossBarOverlay value){overlay=value==null?BossBarOverlay.PROGRESS:value;packet(4);}
        public void properties(boolean dark,boolean music,boolean fog){flags=(byte)((dark?1:0)|(music?2:0)|(fog?4:0));packet(5);}
        public synchronized void animateProgress(float target,Duration duration,Easing easing){cancel(animation);float start=progress,end=clamp(target);int steps=Math.max(1,(int)(valid(duration).toMillis()/50));java.util.concurrent.atomic.AtomicInteger step=new java.util.concurrent.atomic.AtomicInteger();Easing curve=easing==null?Easing.LINEAR:easing;animation=scheduler.scheduleAtFixedRate(()->{if(paused)return;float ratio=Math.min(1F,step.incrementAndGet()/(float)steps);progress(start+(end-start)*curve.apply(ratio));if(ratio>=1F)cancel(animation);},0,50,TimeUnit.MILLISECONDS);}
        @Override public void pause(){if(active&&!paused)packet(1);paused=true;}@Override public void resume(){if(active&&paused){paused=false;packet(0);}}
        private void packet(int action){ProxiedPlayer player=player();if(!active||paused||player==null)return;BossBar packet=new BossBar(barId,action);packet.setTitle(BungeeMessages.component(text,format));packet.setHealth(progress);packet.setColor(color.ordinal());packet.setDivision(overlay.ordinal());packet.setFlags(flags);player.unsafe().sendPacket(packet);}
        public synchronized void close(){if(!active)return;packet(1);active=false;bosses.remove(key,this);cancel(animation);cancel(expiry);}
    }
    private static Duration valid(Duration value){return value==null||value.isNegative()?Duration.ZERO:value;}
    private static int ticks(Duration value){return Math.max(0,(int)(valid(value).toMillis()/50));}
    private static float clamp(float value){return Math.max(0F,Math.min(1F,value));}
    private static PlayerResourceKey key(UUID id,String name){return new PlayerResourceKey(id,name);}
}
