package dev.ikara.cpslimiter;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.UUID;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CpsLimit extends JavaPlugin {

    private int maxCps;
    private long missCooldownMillis;

    // click timestamps per player
    private ConcurrentMap<UUID, Deque<Long>> clickHistory;
    // next valid hit timestamp per player
    private ConcurrentMap<UUID, Long> missCooldownExpiry;

    @Override
    public void onEnable() {
        // load or create config.yml
        saveDefaultConfig();

        // how many clicks per second is allowed
        maxCps = getConfig().getInt("cps-threshold", 20);

        // how long to cancel hits after an over‑CPS hit (seconds)
        double cooldownSec = getConfig().getDouble("hit-miss-cooldown-seconds", 2.0);
        missCooldownMillis = Math.round(cooldownSec * 1000L);

        clickHistory = new ConcurrentHashMap<>();
        missCooldownExpiry = new ConcurrentHashMap<>();

        // register our listener
        getServer().getPluginManager()
                 .registerEvents(new CpsListener(this), this);
    }

    public int getMaxCps() {
        return maxCps;
    }

    public long getMissCooldownMillis() {
        return missCooldownMillis;
    }

    public ConcurrentMap<UUID, Deque<Long>> getClickHistory() {
        return clickHistory;
    }

    public ConcurrentMap<UUID, Long> getMissCooldownExpiry() {
        return missCooldownExpiry;
    }
}
