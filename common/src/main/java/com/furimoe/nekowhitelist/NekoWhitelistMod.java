package com.furimoe.nekowhitelist;

import com.furimoe.nekowhitelist.api.SnapshotCache;
import com.furimoe.nekowhitelist.api.WhitelistConfig;
import com.furimoe.nekowhitelist.mc.JoinGate;
import com.furimoe.nekowhitelist.mc.WhitelistCommand;
import com.furimoe.nekowhitelist.mc.WhitelistSyncService;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Entry point shared by every loader.
 *
 * <p>Both loader modules call {@link #init()} and do nothing else: all the work hangs off
 * Architectury's common lifecycle events, so there is no loader-specific code to keep in
 * sync across versions.
 */
public final class NekoWhitelistMod {

    public static final String MOD_ID = "neko_whitelist";

    private static final Logger LOG = LoggerFactory.getLogger("NekoWhitelist");

    private static WhitelistSyncService service;

    private NekoWhitelistMod() {
    }

    /**
     * The gate the loader-specific login hooks consult, or null while the mod is idle.
     *
     * <p>Architectury has no common login event, so each loader hooks its own and calls
     * back into this.
     */
    public static JoinGate joinGate() {
        return service == null ? null : service.joinGate();
    }

    public static void init() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) ->
                WhitelistCommand.register(dispatcher, () -> service));

        LifecycleEvent.SERVER_STARTED.register(server -> {
            WhitelistConfig config;
            try {
                config = WhitelistConfig.load(Platform.getConfigFolder());
            } catch (IOException e) {
                LOG.error("Could not read {}: {}. Whitelist sync is disabled this session.",
                        WhitelistConfig.FILE_NAME, e.getMessage());
                return;
            }

            if (!config.isConfigured()) {
                LOG.warn("Whitelist sync is idle: set apiKey and instance in config/{}. "
                        + "Find the instance name with GET /api/v1/server/instances.",
                        WhitelistConfig.FILE_NAME);
                return;
            }

            service = new WhitelistSyncService(
                    server, config, new SnapshotCache(Platform.getConfigFolder()), LOG);
            service.start();
        });

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            if (service != null) {
                service.stop();
                service = null;
            }
        });
    }
}
