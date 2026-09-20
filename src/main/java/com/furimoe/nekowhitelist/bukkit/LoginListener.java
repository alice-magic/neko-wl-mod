package com.furimoe.nekowhitelist.bukkit;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Turns away players who are not on the Neko Launcher whitelist.
 *
 * <p>Uses the async pre-login event rather than {@code PlayerLoginEvent}: it fires off
 * the main thread, so a rejection costs the server no tick time, and it carries the
 * authenticated UUID, which is what the whitelist is keyed on.
 */
final class LoginListener implements Listener {

    private final NekoWhitelistPlugin plugin;

    LoginListener(NekoWhitelistPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Runs at MONITOR-adjacent priority so bans and other plugins' refusals are settled
     * first; we only speak for the whitelist and never overturn someone else's decision.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!plugin.isEnforcing()) {
            return;
        }

        // Another plugin already refused them; that is not ours to reconsider.
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }

        if (plugin.isWhitelisted(event.getUniqueId(), event.getName())) {
            return;
        }

        // Ask the API in the background so a player added moments ago can get in on
        // their next attempt. This call returns immediately.
        plugin.lookupInBackground(event.getUniqueId(), event.getName());

        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                "You are not white-listed on this server!");
    }
}
