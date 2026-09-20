package com.furimoe.nekowhitelist.api;

/**
 * An API call that failed. {@link #terminal()} marks the failures that retrying cannot
 * fix — a rotated key or a wrong instance name — so the sync loop stops instead of
 * hammering the API forever.
 */
public class WhitelistApiException extends Exception {

    private final int status;
    private final boolean terminal;

    public WhitelistApiException(int status, String message, boolean terminal) {
        super(message);
        this.status = status;
        this.terminal = terminal;
    }

    public WhitelistApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
        this.terminal = false;
    }

    public int status() {
        return status;
    }

    public boolean terminal() {
        return terminal;
    }
}
