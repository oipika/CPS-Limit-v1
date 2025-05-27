package dev.ikara.cpslimiter;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.server.v1_8_R3.PacketPlayInArmAnimation;
import net.minecraft.server.v1_8_R3.PacketPlayInBlockPlace;
import net.minecraft.server.v1_8_R3.EntityPlayer;
import org.bukkit.craftbukkit.v1_8_R3.entity.CraftPlayer;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.util.Vector;
import org.bukkit.ChatColor;

import java.util.Deque;
import java.util.ArrayDeque;
import java.util.UUID;

public class CpsListener implements Listener {

    private final CpsLimit plugin;

    public CpsListener(CpsLimit plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        EntityPlayer ep = ((CraftPlayer) p).getHandle();
        ChannelPipeline pipe = ep.playerConnection.networkManager.channel.pipeline();
        if (pipe.get("cps-limiter") != null) return;

        pipe.addBefore("packet_handler", "cps-limiter", new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (p.hasPermission("cpslimiter.bypass")) {
                    super.channelRead(ctx, msg);
                    return;
                }
                if (msg instanceof PacketPlayInArmAnimation) {
                    recordClick(p, true);
                } else if (msg instanceof PacketPlayInBlockPlace) {
                    recordClick(p, false);
                }
                super.channelRead(ctx, msg);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        EntityPlayer ep = ((CraftPlayer) p).getHandle();
        ChannelPipeline pipe = ep.playerConnection.networkManager.channel.pipeline();
        if (pipe.get("cps-limiter") != null) {
            pipe.remove("cps-limiter");
        }
    }

    private void recordClick(Player p, boolean isLeft) {
        UUID id = p.getUniqueId();
        CpsLimit.Profile prof = plugin.getProfile(p);

        long nowNs = System.nanoTime();
        long windowNs = 1_000_000_000L + (getPing(p) / 2L) * 1_000_000L;

        Deque<Long> q = (isLeft
            ? plugin.getLeftClickHistory()
            : plugin.getRightClickHistory()
        ).computeIfAbsent(id, k -> new ArrayDeque<>());

        while (!q.isEmpty() && nowNs - q.peekFirst() > windowNs) {
            q.pollFirst();
        }
        q.addLast(nowNs);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        if (p.hasPermission("cpslimiter.bypass")) return;

        UUID id = p.getUniqueId();
        CpsLimit.Profile prof = plugin.getProfile(p);

        long nowNs = System.nanoTime();
        long windowNs = 1_000_000_000L + (getPing(p) / 2L) * 1_000_000L;

        Deque<Long> q = plugin.getRightClickHistory()
                              .computeIfAbsent(id, k -> new ArrayDeque<>());

        while (!q.isEmpty() && nowNs - q.peekFirst() > windowNs) {
            q.pollFirst();
        }

        int cps = q.size();
        if (cps > prof.rightThreshold) {
            e.setCancelled(true);
            plugin.alertDebug("Right-click cancelled for "
                + p.getName()
                + " (R-CPS=" + cps
                + "/" + prof.rightThreshold + ")");
            return;
        }
        q.addLast(nowNs);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) {
            return;
        }

        Player attacker = (Player) e.getDamager();
        if (attacker.hasPermission("cpslimiter.bypass")) return;

        UUID id = attacker.getUniqueId();
        long nowMs = System.currentTimeMillis();
        long nowNs = System.nanoTime();

        CpsLimit.Profile prof = plugin.getProfile(attacker);

        Long expiry = plugin.getMissCooldownExpiry().get(id);
        if (expiry != null && nowMs < expiry) {
            e.setCancelled(true);
            plugin.alertDebug("Hit by " + attacker.getName() + " cancelled (in cooldown)");
            return;
        }

        Deque<Long> q = plugin.getLeftClickHistory().get(id);
        if (q != null) {
            long windowNs = 1_000_000_000L + (getPing(attacker) / 2L) * 1_000_000L;
            while (!q.isEmpty() && nowNs - q.peekFirst() > windowNs) {
                q.pollFirst();
            }
            int cps = q.size();

            if (cps > prof.leftThreshold) {
                long until = nowMs + prof.cooldownMillis;
                plugin.getMissCooldownExpiry().put(id, until);

                e.setCancelled(true);
                attacker.sendMessage(ChatColor.RED +
                    "Hit cancelled: you exceeded " +
                    prof.leftThreshold +
                    " CPS. Cooldown applied.");
                plugin.getLogger().info(
                    attacker.getName() +
                    " penalized for over‑CPS until " +
                    until + "ms");
                plugin.alertDebug("Hit penalized for " +
                    attacker.getName() +
                    " (CPS=" + cps +
                    "/" + prof.leftThreshold +
                    "), cooldown " +
                    (prof.cooldownMillis / 1000.0) +
                    "s");
                return;
            }

            plugin.alertDebug("Hit allowed for " +
                attacker.getName() +
                " (CPS=" + cps +
                "/" + prof.leftThreshold + ")");
        }

        Player victim = (Player) e.getEntity();
        Vector kbDir = victim.getLocation()
                             .toVector()
                             .subtract(attacker.getLocation().toVector())
                             .normalize()
                             .multiply(0.4);
        kbDir.setY(0.1);
        victim.setVelocity(kbDir);
    }

    private int getPing(Player p) {
        try {
            return ((CraftPlayer) p).getHandle().ping;
        } catch (Throwable t) {
            return 100;
        }
    }
}