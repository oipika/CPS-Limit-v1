package dev.ikara.cpslimiter;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;

import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockPlace;
import net.minecraft.server.v1_8_R3.EntityPlayer;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;
import org.bukkit.entity.Player;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;

import org.bukkit.event.entity.EntityDamageByEntityEvent;

import org.bukkit.util.Vector;
import org.bukkit.ChatColor;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * CpsListener:
 *  1) Injects a Netty handler on join to record left/right swing packets (ping‑compensated).
 *  2) On right‑click, enforces a CPS check and penalty if necessary.
 *  3) On entity‑damage (left‑click hits), enforces a CPS check + hit‑miss cooldown + penalty.
 *  4) Also enforces “no_damage” or “mitigate” if the offender is under penalty.
 */
public class CpsListener implements Listener {

    private final CpsLimit plugin;

    public CpsListener(CpsLimit plugin) {
        this.plugin = plugin;
    }

    // ─── Inject Netty handler to record arm‑animation (left) and block‑place (right) packets ─────────────────
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player p = event.getPlayer();
        if (p.hasPermission("cpslimiter.bypass")) {
            return;
        }

        EntityPlayer nmsPlayer = ((CraftPlayer) p).getHandle();
        try {
            Field connField = nmsPlayer.getClass().getDeclaredField("playerConnection");
            connField.setAccessible(true);
            Object playerConn = connField.get(nmsPlayer);

            Field nmField = playerConn.getClass().getDeclaredField("networkManager");
            nmField.setAccessible(true);
            Object networkMgr = nmField.get(playerConn);

            Field chField = networkMgr.getClass().getDeclaredField("channel");
            chField.setAccessible(true);
            io.netty.channel.Channel channel = (io.netty.channel.Channel) chField.get(networkMgr);

            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("cps-limiter") == null) {
                pipeline.addBefore("packet_handler", "cps-limiter", new ChannelDuplexHandler() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                        if (p.hasPermission("cpslimiter.bypass")) {
                            super.channelRead(ctx, msg);
                            return;
                        }
                        if (msg instanceof PacketPlayInArmAnimation) {
                            recordClick(p, /*isLeft=*/ true);
                        } else if (msg instanceof PacketPlayInBlockPlace) {
                            recordClick(p, /*isLeft=*/ false);
                        }
                        super.channelRead(ctx, msg);
                    }
                });
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to inject CPSLimiter handler for " + p.getName());
            ex.printStackTrace();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (p.hasPermission("cpslimiter.bypass")) {
            return;
        }

        // Remove Netty handler and clean up stored data
        EntityPlayer nmsPlayer = ((CraftPlayer) p).getHandle();
        try {
            Field connField = nmsPlayer.getClass().getDeclaredField("playerConnection");
            connField.setAccessible(true);
            Object playerConn = connField.get(nmsPlayer);

            Field nmField = playerConn.getClass().getDeclaredField("networkManager");
            nmField.setAccessible(true);
            Object networkMgr = nmField.get(playerConn);

            Field chField = networkMgr.getClass().getDeclaredField("channel");
            chField.setAccessible(true);
            io.netty.channel.Channel channel = (io.netty.channel.Channel) chField.get(networkMgr);

            if (channel.pipeline().get("cps-limiter") != null) {
                channel.pipeline().remove("cps-limiter");
            }
        } catch (Exception ignored) {
            // ignore any reflection errors on quit
        }

        UUID uid = p.getUniqueId();
        plugin.getLeftClickHistory().remove(uid);
        plugin.getRightClickHistory().remove(uid);
        plugin.getMissCooldownExpiry().remove(uid);

        plugin.getNoDamagePenaltyExpiry().remove(uid);
        plugin.getMitigatePenaltyExpiry().remove(uid);
        plugin.getDebugSubscribers().remove(uid);
    }
    // ────────────────────────────────────────────────────────────────────────────────────────────────────

    // ─── Record a click (left or right) into our sliding window (ping‑compensated) ───────────────────────
    private void recordClick(Player p, boolean isLeft) {
        UUID id   = p.getUniqueId();
        CpsLimit.Profile prof = plugin.getProfile(p);

        long nowNs = System.nanoTime();
        long pingMs = getPing(p);
        long windowNs = 1_000_000_000L + (pingMs / 2L) * 1_000_000L;

        Deque<Long> q = (isLeft
            ? plugin.getLeftClickHistory()
            : plugin.getRightClickHistory()
        ).computeIfAbsent(id, k -> new ArrayDeque<>());

        while (!q.isEmpty() && nowNs - q.peekFirst() > windowNs) {
            q.pollFirst();
        }
        q.addLast(nowNs);
    }
    // ────────────────────────────────────────────────────────────────────────────────────────────────────

    // ─── Handle right‑clicks (cancel if over threshold, then apply penalty) ─────────────────────────────
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player p = event.getPlayer();
        if (p.hasPermission("cpslimiter.bypass")) {
            return;
        }

        UUID id   = p.getUniqueId();
        CpsLimit.Profile prof = plugin.getProfile(p);

        long nowNs = System.nanoTime();
        long pingMs = getPing(p);
        long windowNs = 1_000_000_000L + (pingMs / 2L) * 1_000_000L;

        Deque<Long> q = plugin.getRightClickHistory().computeIfAbsent(id, k -> new ArrayDeque<>());
        while (!q.isEmpty() && nowNs - q.peekFirst() > windowNs) {
            q.pollFirst();
        }

        int cps = q.size();
        if (cps > prof.rightThreshold) {
            event.setCancelled(true);
            plugin.alertDebug("Right‑click penalty for " + p.getName()
                + " (R‑CPS=" + cps + "/" + prof.rightThreshold + ")");
            applyPenalty(p, prof);
            return;
        }

        q.addLast(nowNs);
    }
    // ────────────────────────────────────────────────────────────────────────────────────────────────────

    // ─── Handle left‑click hits (EntityDamageByEntity) ─────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();

        if (attacker.hasPermission("cpslimiter.bypass")) {
            return;
        }

        UUID id = attacker.getUniqueId();
        CpsLimit.Profile prof = plugin.getProfile(attacker);
        long nowMs = System.currentTimeMillis();
        long nowNs = System.nanoTime();

        // ─── “no_damage” penalty check ────────────────────────────────────────────────────────────
        Long noDamageExpiry = plugin.getNoDamagePenaltyExpiry().get(id);
        if (noDamageExpiry != null) {
            if (nowMs < noDamageExpiry) {
                event.setCancelled(true);
                return;
            } else {
                plugin.getNoDamagePenaltyExpiry().remove(id);
            }
        }

        // ─── “mitigate” penalty check ──────────────────────────────────────────────────────────────
        Long mitigateExpiry = plugin.getMitigatePenaltyExpiry().get(id);
        if (mitigateExpiry != null) {
            if (nowMs < mitigateExpiry) {
                double originalDamage = event.getDamage();
                double factor = prof.mitigatePercentage / 100.0;
                event.setDamage(originalDamage * factor);
                return;
            } else {
                plugin.getMitigatePenaltyExpiry().remove(id);
            }
        }

        // ─── Hit‑miss cooldown & CPS threshold check ───────────────────────────────────────────────
        Long cooldownExpiry = plugin.getMissCooldownExpiry().get(id);
        if (cooldownExpiry != null && nowMs < cooldownExpiry) {
            event.setCancelled(true);
            plugin.alertDebug("Hit by " + attacker.getName() + " cancelled (in cooldown)");
            return;
        }

        Deque<Long> q = plugin.getLeftClickHistory().get(id);
        if (q != null) {
            long pingMs = getPing(attacker);
            long windowNs = 1_000_000_000L + (pingMs / 2L) * 1_000_000L;

            while (!q.isEmpty() && nowNs - q.peekFirst() > windowNs) {
                q.pollFirst();
            }
            int cps = q.size();

            if (cps > prof.leftThreshold) {
                long untilMs = nowMs + prof.cooldownMillis;
                plugin.getMissCooldownExpiry().put(id, untilMs);

                event.setCancelled(true);
                plugin.alertDebug("Hit penalized for " + attacker.getName()
                    + " (L‑CPS=" + cps + "/" + prof.leftThreshold + "), cooldown="
                    + (prof.cooldownMillis / 1000.0) + "s");
                applyPenalty(attacker, prof);
                return;
            } else {
                plugin.alertDebug("Hit allowed for " + attacker.getName()
                    + " (L‑CPS=" + cps + "/" + prof.leftThreshold + ")");
            }
        }

        // ─── Normal knockback ─────────────────────────────────────────────────────────────────────
        Vector kbDir = victim.getLocation()
                             .toVector()
                             .subtract(attacker.getLocation().toVector())
                             .normalize()
                             .multiply(0.4);
        kbDir.setY(0.1);
        victim.setVelocity(kbDir);
    }
    // ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Returns the player’s ping in milliseconds; defaults to 100 if reflection fails. */
    private int getPing(Player p) {
        try {
            return ((CraftPlayer) p).getHandle().ping;
        } catch (Throwable t) {
            return 100;
        }
    }

    // ─── Apply whichever penalty this profile dictates ─────────────────────────────────────────────────
    private void applyPenalty(Player offender, CpsLimit.Profile prof) {
        String type = prof.penaltyType; // “kick” / “no_damage” / “mitigate”
        int mPct = prof.mitigatePercentage; // only used if “mitigate”
        long nowMs = System.currentTimeMillis();
        long expiryMs = nowMs + (long) (plugin.getPenaltyDurationSeconds() * 1000.0);

        switch (type) {
            case "kick":
                offender.kickPlayer("§cYou have been kicked for exceeding CPS limits.");
                break;

            case "no_damage":
                plugin.getNoDamagePenaltyExpiry().put(offender.getUniqueId(), expiryMs);
                offender.sendMessage(ChatColor.RED
                    + "You have exceeded the CPS limit. You cannot deal damage for "
                    + String.format("%.1f", plugin.getPenaltyDurationSeconds())
                    + " seconds."
                );
                break;

            case "mitigate":
                plugin.getMitigatePenaltyExpiry().put(offender.getUniqueId(), expiryMs);
                offender.sendMessage(ChatColor.RED
                    + "Your CPS was too high. Until penalty expires, your damage is reduced by "
                    + mPct
                    + "%."
                );
                break;

            default:
                // Should not happen; fallback to “no_damage”
                plugin.getNoDamagePenaltyExpiry().put(offender.getUniqueId(), expiryMs);
                offender.sendMessage(ChatColor.RED
                    + "Invalid penalty type. Defaulting to no_damage for "
                    + String.format("%.1f", plugin.getPenaltyDurationSeconds())
                    + " seconds."
                );
                break;
        }
    }
}
