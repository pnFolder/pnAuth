package ru.privatenull.pnauth.display;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
public final class NoopPlayerDisplay implements PlayerDisplay {
    public ActionBarHandle actionBar(UUID id,String name,ActionBarOptions value){return new Action(id,name);}
    public TitleHandle title(UUID id,String name,TitleOptions value){return new Titles(id,name);}
    public BossBarHandle bossBar(UUID id,String name,BossBarOptions value){return new Boss(id,name);}
    public Optional<ActionBarHandle> findActionBar(UUID id,String name){return Optional.empty();}
    public Optional<TitleHandle> findTitle(UUID id,String name){return Optional.empty();}
    public Optional<BossBarHandle> findBossBar(UUID id,String name){return Optional.empty();}
    public boolean removeActionBar(UUID id,String name){return false;} public boolean removeTitle(UUID id,String name){return false;} public boolean removeBossBar(UUID id,String name){return false;}
    public void clear(UUID id){}
    private abstract static class Base implements DisplayHandle { final UUID id;final String name;boolean active=true,paused;Base(UUID id,String name){this.id=id;this.name=name;}
        public UUID playerId(){return id;}public String displayId(){return name;}public boolean active(){return active;}public boolean paused(){return paused;}public void pause(){paused=true;}public void resume(){paused=false;}public void lifetime(Duration value){}public void close(){active=false;} }
    private static final class Action extends Base implements ActionBarHandle {Action(UUID id,String name){super(id,name);}public void text(String v){}public void refreshInterval(Duration v){}public void sendNow(){}}
    private static final class Titles extends Base implements TitleHandle {Titles(UUID id,String name){super(id,name);}public void title(String v){}public void subtitle(String v){}public void timings(Duration a,Duration b,Duration c){}public void repeatInterval(Duration v){}public void showNow(){}public void clear(){}}
    private static final class Boss extends Base implements BossBarHandle {Boss(UUID id,String name){super(id,name);}public void text(String v){}public void progress(float v){}public void color(BossBarColor v){}public void overlay(BossBarOverlay v){}public void properties(boolean a,boolean b,boolean c){}public void animateProgress(float t,Duration d,Easing e){}}
}
