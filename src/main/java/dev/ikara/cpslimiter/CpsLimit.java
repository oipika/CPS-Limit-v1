package dev.ikara.cpslimiter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.Deque;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class CpsLimit extends JavaPlugin {

    public static class Profile {
        public final String permission;
        public final int leftThreshold;
        public final int rightThreshold;
        public final long cooldownMillis;

        public Profile(String permission, int leftThreshold, int rightThreshold, long cooldownMillis) {
            this.permission = permission;
            this.leftThreshold = leftThreshold;
            this.rightThreshold = rightThreshold;
            this.cooldownMillis = cooldownMillis;
        }
        public boolean matches(Player p) {
            return permission != null && !permission.isEmpty() && p.hasPermission(permission);
        }
    }

    private Profile defaultProfile;
    private List<Profile> profiles;

    private ConcurrentMap<UUID, Deque<Long>> leftClickHistory;
    private ConcurrentMap<UUID, Deque<Long>> rightClickHistory;
    private ConcurrentMap<UUID, Long> missCooldownExpiry;
    private Set<UUID> debugSubscribers;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        int flatDefault = getConfig().getInt("cps-threshold-default", 20);
        int defaultLeft = getConfig().getInt("cps-threshold.left", flatDefault);
        int defaultRight = getConfig().getInt("cps-threshold.right", defaultLeft);
        double flatCd = getConfig().getDouble("hit-miss-cooldown-seconds", 1.0);
        long defaultCdMs = Math.round(flatCd * 1000L);

        defaultProfile = new Profile(
            /*permission=*/ null,
            /*leftThreshold=*/  defaultLeft,
            /*rightThreshold=*/ defaultRight,
            /*cooldownMillis=*/ defaultCdMs
        );

        profiles = new ArrayList<>();
        if (getConfig().isConfigurationSection("profiles")) {
            ConfigurationSection root = getConfig().getConfigurationSection("profiles");
            for (String key : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(key);
                String perm = sec.getString("permission", "");
                int l = sec.getInt("cps-threshold.left",  defaultLeft);
                int r = sec.getInt("cps-threshold.right", defaultRight);
                double cd = sec.getDouble("hit-miss-cooldown-seconds", flatCd);
                profiles.add(new Profile(perm, l, r, Math.round(cd * 1000L)));
            }
        }

        leftClickHistory = new ConcurrentHashMap<>();
        rightClickHistory = new ConcurrentHashMap<>();
        missCooldownExpiry = new ConcurrentHashMap<>();
        debugSubscribers = ConcurrentHashMap.newKeySet();

        getServer().getPluginManager()
                   .registerEvents(new CpsListener(this), this);
        getCommand("cpsdebug").setExecutor(new CpsDebugCommand(this));
    }

    /** Returns the matching profile or the default one. */
    public Profile getProfile(Player p) {
        for (Profile prof : profiles) {
            if (prof.matches(p)) return prof;
        }
        return defaultProfile;
    }

    public ConcurrentMap<UUID, Deque<Long>> getLeftClickHistory() { return leftClickHistory; }
    public ConcurrentMap<UUID, Deque<Long>> getRightClickHistory() { return rightClickHistory; }
    public ConcurrentMap<UUID, Long> getMissCooldownExpiry(){ return missCooldownExpiry; }
    public Set<UUID> getDebugSubscribers()  { return debugSubscribers; }

    /** Send a debug alert to every subscribed player. */
    public void alertDebug(String message) {
        String prefix = "§7[CPSDebug] ";
        for (UUID uid : debugSubscribers) {
            Player p = getServer().getPlayer(uid);
            if (p != null) {
                p.sendMessage(prefix + message);
            }
        }
    }
}