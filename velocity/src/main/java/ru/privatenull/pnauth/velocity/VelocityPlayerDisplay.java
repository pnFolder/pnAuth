package ru.privatenull.pnauth.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import ru.privatenull.pnauth.display.*;
import ru.privatenull.pnauth.message.MessageFormat;
import ru.privatenull.pnauth.platform.PlayerResourceKey;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;

final class VelocityPlayerDisplay implements PlayerDisplay, AutoCloseable {
    private final ProxyServer proxy; private final MessageFormat format;
    private final ConcurrentMap<PlayerResourceKey,Action> actions=new ConcurrentHashMap<>();private final ConcurrentMap<PlayerResourceKey,Titles> titles=new ConcurrentHashMap<>();private final ConcurrentMap<PlayerResourceKey,Boss> bosses=new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2,r->{Thread t=new Thread(r,"pnauth-velocity-display");t.setDaemon(true);return t;});
    VelocityPlayerDisplay(ProxyServer proxy,MessageFormat format){this.proxy=proxy;this.format=format;}
    public ActionBarHandle actionBar(UUID id,String name,ActionBarOptions o){PlayerResourceKey k=key(id,name);return actions.compute(k,(x,v)->{if(v==null||!v.active)return new Action(k,o);v.apply(o);return v;});}
    public TitleHandle title(UUID id,String name,TitleOptions o){PlayerResourceKey k=key(id,name);return titles.compute(k,(x,v)->{if(v==null||!v.active)return new Titles(k,o);v.apply(o);return v;});}
    public BossBarHandle bossBar(UUID id,String name,BossBarOptions o){PlayerResourceKey k=key(id,name);return bosses.compute(k,(x,v)->{if(v==null||!v.active)return new Boss(k,o);v.apply(o);return v;});}
    public java.util.Optional<ActionBarHandle> findActionBar(UUID id,String name){return java.util.Optional.ofNullable(actions.get(key(id,name)));}public java.util.Optional<TitleHandle> findTitle(UUID id,String name){return java.util.Optional.ofNullable(titles.get(key(id,name)));}public java.util.Optional<BossBarHandle> findBossBar(UUID id,String name){return java.util.Optional.ofNullable(bosses.get(key(id,name)));}
    public boolean removeActionBar(UUID id,String name){Action h=actions.remove(key(id,name));if(h!=null)h.close();return h!=null;}public boolean removeTitle(UUID id,String name){Titles h=titles.remove(key(id,name));if(h!=null)h.close();return h!=null;}public boolean removeBossBar(UUID id,String name){Boss h=bosses.remove(key(id,name));if(h!=null)h.close();return h!=null;}
    public void clear(UUID id){actions.entrySet().stream().filter(e->e.getKey().playerId.equals(id)).map(java.util.Map.Entry::getValue).toList().forEach(Action::close);titles.entrySet().stream().filter(e->e.getKey().playerId.equals(id)).map(java.util.Map.Entry::getValue).toList().forEach(Titles::close);bosses.entrySet().stream().filter(e->e.getKey().playerId.equals(id)).map(java.util.Map.Entry::getValue).toList().forEach(Boss::close);}
    public void close(){actions.values().forEach(Action::close);titles.values().forEach(Titles::close);bosses.values().forEach(Boss::close);scheduler.shutdownNow();}
    private abstract class Base implements DisplayHandle{
        final PlayerResourceKey key;final UUID playerId;volatile boolean active=true,paused;volatile ScheduledFuture<?> expiry;
        Base(PlayerResourceKey key,Duration lifetime){this.key=key;playerId=key.playerId;lifetime(lifetime);}public UUID playerId(){return playerId;}public String displayId(){return key.name;}public boolean active(){return active;}public boolean paused(){return paused;}
        public void pause(){paused=true;}public void resume(){if(active)paused=false;}public synchronized void lifetime(Duration value){cancel(expiry);Duration d=valid(value);if(!d.isZero())expiry=scheduler.schedule(this::close,d.toMillis(),TimeUnit.MILLISECONDS);}
        Player player(){return proxy.getPlayer(playerId).orElse(null);}void cancel(ScheduledFuture<?> task){if(task!=null)task.cancel(false);}
    }
    private final class Action extends Base implements ActionBarHandle{
        volatile String text;volatile Duration interval;volatile ScheduledFuture<?> refresh;
        Action(PlayerResourceKey key,ActionBarOptions o){super(key,o.lifetime());text=o.text();interval=o.refreshInterval();sendNow();reschedule();}void apply(ActionBarOptions o){text(o.text());refreshInterval(o.refreshInterval());lifetime(o.lifetime());}
        public void text(String value){text=value==null?"":value;sendNow();}public synchronized void refreshInterval(Duration value){interval=valid(value);reschedule();}
        private synchronized void reschedule(){cancel(refresh);if(active&&!interval.isZero())refresh=scheduler.scheduleAtFixedRate(this::sendNow,interval.toMillis(),interval.toMillis(),TimeUnit.MILLISECONDS);}
        public void sendNow(){Player p=player();if(active&&!paused&&p!=null)p.sendActionBar(VelocityMessages.component(text,format));}
        public synchronized void close(){if(!active)return;active=false;actions.remove(key,this);cancel(refresh);cancel(expiry);Player p=player();if(p!=null)p.sendActionBar(net.kyori.adventure.text.Component.empty());}
    }
    private final class Titles extends Base implements TitleHandle{
        volatile String title,subtitle;volatile Duration fadeIn,stay,fadeOut,repeat;volatile ScheduledFuture<?> refresh;
        Titles(PlayerResourceKey key,TitleOptions o){super(key,o.lifetime());title=o.title();subtitle=o.subtitle();fadeIn=o.fadeIn();stay=o.stay();fadeOut=o.fadeOut();repeat=o.repeatInterval();showNow();reschedule();}void apply(TitleOptions o){title=o.title();subtitle=o.subtitle();timings(o.fadeIn(),o.stay(),o.fadeOut());repeatInterval(o.repeatInterval());lifetime(o.lifetime());showNow();}
        public void title(String value){title=value==null?"":value;showNow();}public void subtitle(String value){subtitle=value==null?"":value;showNow();}public void timings(Duration a,Duration b,Duration c){fadeIn=valid(a);stay=valid(b);fadeOut=valid(c);}
        public synchronized void repeatInterval(Duration value){repeat=valid(value);reschedule();}private synchronized void reschedule(){cancel(refresh);if(active&&!repeat.isZero())refresh=scheduler.scheduleAtFixedRate(this::showNow,repeat.toMillis(),repeat.toMillis(),TimeUnit.MILLISECONDS);}
        public void showNow(){Player p=player();if(active&&!paused&&p!=null)p.showTitle(Title.title(VelocityMessages.component(title,format),VelocityMessages.component(subtitle,format),Title.Times.times(fadeIn,stay,fadeOut)));}
        public void clear(){Player p=player();if(p!=null)p.clearTitle();}public synchronized void close(){if(!active)return;active=false;titles.remove(key,this);cancel(refresh);cancel(expiry);clear();}
    }
    private final class Boss extends Base implements BossBarHandle{
        final BossBar bar;volatile float progress;volatile ScheduledFuture<?> animation;
        Boss(PlayerResourceKey key,BossBarOptions o){super(key,o.lifetime());progress=o.progress();bar=BossBar.bossBar(VelocityMessages.component(o.text(),format),progress,colorOf(o.color()),overlayOf(o.overlay()));properties(o.darkenScreen(),o.playBossMusic(),o.createWorldFog());Player p=player();if(p!=null)p.showBossBar(bar);}void apply(BossBarOptions o){text(o.text());progress(o.progress());color(o.color());overlay(o.overlay());properties(o.darkenScreen(),o.playBossMusic(),o.createWorldFog());lifetime(o.lifetime());}
        public void text(String value){bar.name(VelocityMessages.component(value,format));}public void progress(float value){progress=clamp(value);bar.progress(progress);}public void color(BossBarColor value){bar.color(colorOf(value));}public void overlay(BossBarOverlay value){bar.overlay(overlayOf(value));}
        public void properties(boolean dark,boolean music,boolean fog){java.util.Set<BossBar.Flag> flags=new java.util.HashSet<>();if(dark)flags.add(BossBar.Flag.DARKEN_SCREEN);if(music)flags.add(BossBar.Flag.PLAY_BOSS_MUSIC);if(fog)flags.add(BossBar.Flag.CREATE_WORLD_FOG);bar.flags(flags);}
        public synchronized void animateProgress(float target,Duration duration,Easing easing){cancel(animation);float start=progress,end=clamp(target);int steps=Math.max(1,(int)(valid(duration).toMillis()/50));java.util.concurrent.atomic.AtomicInteger step=new java.util.concurrent.atomic.AtomicInteger();Easing curve=easing==null?Easing.LINEAR:easing;animation=scheduler.scheduleAtFixedRate(()->{if(paused)return;float ratio=Math.min(1F,step.incrementAndGet()/(float)steps);progress(start+(end-start)*curve.apply(ratio));if(ratio>=1F)cancel(animation);},0,50,TimeUnit.MILLISECONDS);}
        @Override public void pause(){if(!active||paused)return;paused=true;Player p=player();if(p!=null)p.hideBossBar(bar);}@Override public void resume(){if(!active||!paused)return;paused=false;Player p=player();if(p!=null)p.showBossBar(bar);}
        public synchronized void close(){if(!active)return;active=false;bosses.remove(key,this);cancel(animation);cancel(expiry);Player p=player();if(p!=null)p.hideBossBar(bar);}
    }
    private static Duration valid(Duration value){return value==null||value.isNegative()?Duration.ZERO:value;}private static float clamp(float value){return Math.max(0F,Math.min(1F,value));}
    private static BossBar.Color colorOf(BossBarColor c){return BossBar.Color.valueOf((c==null?BossBarColor.PURPLE:c).name());}
    private static BossBar.Overlay overlayOf(BossBarOverlay o){return BossBar.Overlay.valueOf((o==null?BossBarOverlay.PROGRESS:o).name());}
    private static PlayerResourceKey key(UUID id,String name){return new PlayerResourceKey(id,name);}
}
