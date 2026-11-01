/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/**
 * The two scheduled tick phases of a level tick that can execute on region workers.
 * <p>
 * The phases are executed one after the other by the server thread, and each phase waits for its worker
 * batch before the next one starts. A block tick and a fluid tick of the same region therefore never
 * overlap, even though they belong to different {@code LevelTicks} instances.
 */
public enum ScheduledTickPhase {
    BLOCK("block"),
    FLUID("fluid");

    private final String description;

    ScheduledTickPhase(String description) {
        this.description = description;
    }

    public String description() {
        return this.description;
    }
}
