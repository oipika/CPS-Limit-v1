package dev.ikara.cpslimiter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * author: oipika
 * date: 5/31/2025
 */
public class CpsLimit extends JavaPlugin {

    /** Represents one “profile” block in config.yml. */
    public static class Profile {
        public final String permission;
        public final int leftThreshold;
        public final int rightThreshold;
        public final long cooldownMillis;

        // ─── NEW PER‑PROFILE FIELDS ─────────────────────────────
        // Which penalty to apply if CPS is exceeded:
        // “kick” → kick the player immediately
        // “no_damage” → block all damage for duration
        // “mitigate” → reduce damage by (mitigatePercentage/100.0)
        public final String penaltyType;

        // If penaltyType == "mitigate", how much to reduce (0–100).
        public final int mitigatePercentage;
        // ─────────────────────────────────────────────────────────

        public Profile(String permission,
                       int leftThreshold,
                       int rightThreshold,
                       long cooldownMillis,
                       String penaltyType,
                       int mitigatePercentage)
        {
            this.permission = permission;
            this.leftThreshold = leftThreshold;
            this.rightThreshold = rightThreshold;
            this.cooldownMillis = cooldownMillis;
            this.penaltyType = penaltyType;
            this.mitigatePercentage = mitigatePercentage;
        }

        /** Returns true if this profile matches the given player’s permission. */
        public boolean matches(Player p) {
            return permission != null
                && !permission.isEmpty()
                && p.hasPermission(permission);
        }
    }

    // ─── Configuration Defaults for “Default Profile” ─────────────────
    // Loaded from top‑level keys in onEnable():
    private String defaultPenaltyType;
    private int    defaultMitigatePercentage;
    private double penaltyDurationSeconds;
    // ──────────────────────────────────────────────────────────────────

    private Profile defaultProfile;
    private final List<Profile> profiles = new ArrayList<>();

    // ─── CPSLimiter fields ────────────────────────────────────────────
    private final ConcurrentMap<UUID, Deque<Long>> leftClickHistory    = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Deque<Long>> rightClickHistory   = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> missCooldownExpiry  = new ConcurrentHashMap<>();
    private final Set<UUID> debugSubscribers    = ConcurrentHashMap.newKeySet();
    // ──────────────────────────────────────────────────────────────────

    // ─── Penalty‑Expiry Maps ──────────────────────────────────────────
    // Tracks when “no_damage” should expire (millis) for each offender:
    private final ConcurrentMap<UUID, Long> noDamagePenaltyExpiry   = new ConcurrentHashMap<>();
    // Tracks when “mitigate” should expire (millis) for each offender:
    private final ConcurrentMap<UUID, Long> mitigatePenaltyExpiry  = new ConcurrentHashMap<>();
    // ──────────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfiguration();

        // Register our listener and command exactly as before:
        getServer().getPluginManager().registerEvents(new CpsListener(this), this);
        getCommand("cpsdebug").setExecutor(new CpsDebugCommand(this));
    }

    /** Reads all config values (thresholds, profiles, and new penalty keys). */
    private void loadConfiguration() {
        // ─── Read global penalty defaults ─────────────────────────────────
        defaultPenaltyType = getConfig().getString("penalty-type", "no_damage").toLowerCase(Locale.ROOT);
        defaultMitigatePercentage = getConfig().getInt("mitigate-percentage", 50);
        penaltyDurationSeconds = getConfig().getDouble("penalty-duration-seconds", 5.0);

        // Validate penaltyType:
        if (!defaultPenaltyType.equals("kick")
         && !defaultPenaltyType.equals("no_damage")
         && !defaultPenaltyType.equals("mitigate"))
        {
            getLogger().warning("Invalid global penalty-type '"
                + defaultPenaltyType
                + "'. Defaulting to 'no_damage'.");
            defaultPenaltyType = "no_damage";
        }

        // Clamp mitigatePercentage to [0..100]:
        if (defaultMitigatePercentage < 0)    defaultMitigatePercentage = 0;
        if (defaultMitigatePercentage > 100)  defaultMitigatePercentage = 100;
        // ──────────────────────────────────────────────────────────────────

        // ─── Read defaultProfile thresholds & per‑profile overrides ────────
        // First, read the global CPS thresholds & hit-miss cooldown:
        int flatDefault = getConfig().getInt("cps-threshold-default.left", 18);
        int defaultLeft = getConfig().getInt("cps-threshold-default.left", flatDefault);
        int defaultRight = getConfig().getInt("cps-threshold-default.right", defaultLeft);
        double flatCooldownSec = getConfig().getDouble("hit-miss-cooldown-seconds", 1.0);
        long defaultCooldownMs = Math.round(flatCooldownSec * 1000.0);

        // Build the “defaultProfile” using the global values and default penalty keys:
        defaultProfile = new Profile(
            /*permission=*/ null,
            /*leftThreshold=*/ defaultLeft,
            /*rightThreshold=*/ defaultRight,
            /*cooldownMillis=*/ defaultCooldownMs,
            /*penaltyType=*/ defaultPenaltyType,
            /*mitigatePercentage=*/ defaultMitigatePercentage
        );

        // Now load any named profiles under “profiles:” in config.yml:
        profiles.clear();
        if (getConfig().isConfigurationSection("profiles")) {
            ConfigurationSection root = getConfig().getConfigurationSection("profiles");
            for (String key : root.getKeys(false)) {
                ConfigurationSection sec = root.getConfigurationSection(key);

                String perm = sec.getString("permission", "").trim();

                // Per‑profile CPS thresholds (fall back to defaultLeft/defaultRight if missing):
                int profLeft = sec.getInt("cps-threshold.left",  defaultLeft);
                int profRight = sec.getInt("cps-threshold.right", defaultRight);
                double cdSec = sec.getDouble("hit-miss-cooldown-seconds", flatCooldownSec);
                long profCdMs = Math.round(cdSec * 1000.0);

                // ─── NEW: per‐profile penalty overrides ──────────────────────────
                String pType = sec.getString("penalty-type", defaultPenaltyType).toLowerCase(Locale.ROOT);
                int mPct = sec.getInt("mitigate-percentage", defaultMitigatePercentage);

                // Validate pType for this profile:
                if (!pType.equals("kick")
                 && !pType.equals("no_damage")
                 && !pType.equals("mitigate"))
                {
                    getLogger().warning("Invalid penalty-type '"
                        + pType
                        + "' for profile '"
                        + key
                        + "'. Using default '" + defaultPenaltyType + "'.");
                    pType = defaultPenaltyType;
                }

                // Clamp mPct to [0..100]:
                if (mPct < 0) mPct = 0;
                if (mPct > 100) mPct = 100;
                // ────────────────────────────────────────────────────────────────

                Profile prof = new Profile(
                    /*permission=*/ perm,
                    /*leftThreshold=*/ profLeft,
                    /*rightThreshold=*/ profRight,
                    /*cooldownMillis=*/ profCdMs,
                    /*penaltyType=*/ pType,
                    /*mitigatePercentage=*/ mPct
                );
                profiles.add(prof);
            }
        }
        // ──────────────────────────────────────────────────────────────────
    }

    /** Returns the first matching profile (by permission), or the defaultProfile. */
    public Profile getProfile(Player p) {
        for (Profile prof : profiles) {
            if (prof.matches(p)) {
                return prof;
            }
        }
        return defaultProfile;
    }

    // ─── Getters for CPSLimiter’s internal maps ────────────────────────
    public ConcurrentMap<UUID, Deque<Long>> getLeftClickHistory()  { return leftClickHistory; }
    public ConcurrentMap<UUID, Deque<Long>> getRightClickHistory() { return rightClickHistory; }
    public ConcurrentMap<UUID, Long> getMissCooldownExpiry(){ return missCooldownExpiry; }
    public Set<UUID> getDebugSubscribers()  { return debugSubscribers; }
    public ConcurrentMap<UUID, Long> getNoDamagePenaltyExpiry() { return noDamagePenaltyExpiry; }
    public ConcurrentMap<UUID, Long> getMitigatePenaltyExpiry() { return mitigatePenaltyExpiry; }
    public double getPenaltyDurationSeconds() { return penaltyDurationSeconds; }
    // ──────────────────────────────────────────────────────────────────

    /** Sends a debug message to everyone with /cpsdebug toggled on. */
    public void alertDebug(String message) {
        String prefix = "§7[CPSDebug] ";
        for (UUID uid : debugSubscribers) {
            if (uid == null) continue;
            Player p = getServer().getPlayer(uid);
            if (p != null && p.isOnline()) {
                p.sendMessage(prefix + message);
            }
        }
    }
}
