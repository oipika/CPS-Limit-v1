package dev.ikara.cpslimiter;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * /cpsdebug toggle: players see live CPS debug messages when toggled on.
 */
public class CpsDebugCommand implements CommandExecutor {

    private final CpsLimit plugin;

    public CpsDebugCommand(CpsLimit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can toggle debug alerts.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("cpslimiter.debug")) {
            p.sendMessage("§cYou don’t have permission.");
            return true;
        }

        UUID id = p.getUniqueId();
        if (plugin.getDebugSubscribers().contains(id)) {
            plugin.getDebugSubscribers().remove(id);
            p.sendMessage("§cCPS debug alerts disabled.");
        } else {
            plugin.getDebugSubscribers().add(id);
            p.sendMessage("§aYou are now seeing live CPS debug alerts.");
        }
        return true;
    }
}
