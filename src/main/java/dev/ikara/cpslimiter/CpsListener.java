package dev.ikara.cpslimiter;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.util.Vector;
import org.bukkit.ChatColor;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.UUID;

public class CpsListener implements Listener {

    private final CpsLimit plugin;

    public CpsListener(CpsLimit plugin) {
        this.plugin = plugin;
    }

    // Record every left‑click, enforce sliding 1 s + ping window
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_AIR && a != Action.LEFT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        long now = System.currentTimeMillis();
        int ping = getPing(p);
        long window = 1000L + ping;

        Deque<Long> q = plugin.getClickHistory()
                              .computeIfAbsent(id, k -> new ArrayDeque<>());

        while (!q.isEmpty() && now - q.peekFirst() > window) {
            q.pollFirst();
        }

        if (q.size() >= plugin.getMaxCps()) {
            e.setCancelled(true);
            return;
        }

        q.addLast(now);
    }

    // When someone hits another player:
    // • if still in cooldown, cancel
    // • if CPS > threshold, start cooldown + cancel
    // • else apply a fixed 1.8‑style KB
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) {
            return;
        }

        Player attacker = (Player) e.getDamager();
        UUID id = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        // still cooling down?
        Long expiry = plugin.getMissCooldownExpiry().get(id);
        if (expiry != null && now < expiry) {
            e.setCancelled(true);
            return;
        }

        // re‑check CPS at the moment of hit
        Deque<Long> q = plugin.getClickHistory().get(id);
        if (q != null) {
            int ping = getPing(attacker);
            long window = 1000L + ping;
            while (!q.isEmpty() && now - q.peekFirst() > window) {
                q.pollFirst();
            }
            if (q.size() > plugin.getMaxCps()) {
                plugin.getMissCooldownExpiry()
                      .put(id, now + plugin.getMissCooldownMillis());
                e.setCancelled(true);
                attacker.sendMessage(ChatColor.RED +
                    "Hit cancelled: you exceeded " +
                    plugin.getMaxCps() +
                    " CPS. Cooldown applied.");
                return;
            }
        }

        // apply consistent vanilla‑1.8 knockback
        Player victim = (Player) e.getEntity();
        Vector kbDir = victim.getLocation()
                             .toVector()
                             .subtract(attacker.getLocation().toVector())
                             .normalize()
                             .multiply(0.4);
        kbDir.setY(0.1);
        victim.setVelocity(kbDir);
    }

    // fetch ping on 1.8 without reflection
    private int getPing(Player p) {
        try {
            return ((CraftPlayer) p).getHandle().ping;
        } catch (Throwable t) {
            return 100;
        }
    }
}