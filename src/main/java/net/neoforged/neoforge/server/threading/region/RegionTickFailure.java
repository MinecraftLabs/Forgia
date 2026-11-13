/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.ReportedException;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForgeConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One tick failure that a region worker caught and recorded for the server thread to report.
 * <p>
 * Vanilla reports a failing tick by building a {@link CrashReport}. Building one enumerates the system hardware
 * through {@code oshi}/{@code JNA}, reads the memory, storage and JVM details and allocates the whole text of the
 * report, and it does so on the thread that failed. On a region worker that thread runs while the server thread
 * sits in the batch barrier waiting for it, so a single failing tick has already been observed to turn into an
 * out-of-memory or a hang. A worker therefore only records the throwable and the context it was ticking, and the
 * server thread reports the failure after the barrier and after the handoff of the batch: the report, the log
 * output and the shutdown stay vanilla's, only the thread that produces them changed.
 * <p>
 * The recorded context is what the report cannot carry: the report is built later, on the server thread, so the
 * slice, the region, the tick and the worker that failed are only known from this record.
 *
 * @param slice        the level tick slice the failure happened in
 * @param coordinate   the region whose worker group failed
 * @param tick         the game time of the level tick that failed
 * @param entity       the entity whose tick failed, or {@code null} for a unit that vanilla reports at the tick
 *                     boundary without naming it - a block-entity ticker, a chunk, a scheduled tick, a spawn
 * @param workerThread the name of the worker thread that recorded the failure
 * @param throwable    the failure
 */
@ApiStatus.Internal
record RegionTickFailure(RegionTickSlice slice, RegionCoordinate coordinate, long tick, @Nullable Entity entity, String workerThread, Throwable throwable) {

    private static final Logger LOGGER = LogManager.getLogger();
    /**
     * Reports this failure on the calling server thread, exactly like the vanilla tick would have reported it.
     * <p>
     * An entity tick failure keeps vanilla's guarded entity tick: the same {@code Ticking entity} report, the
     * same {@code Entity being ticked} category filled from the entity, and the same
     * {@code removeErroringEntities} branch that logs the report and discards the entity instead of throwing.
     * Every other slice is reported by the vanilla tick boundary that always reported it, so its throwable is
     * rethrown unchanged and that boundary builds the report there.
     */
    void report() {
        if (this.entity != null) {
            reportEntityTickFailure(this.entity, this.throwable);
            return;
        }
        rethrow(this.throwable);
    }

    /**
     * Reports {@code throwable} for the entity whose tick failed, exactly like the catch block of
     * {@code Level#guardEntityTick} does on the thread that ticks the entity.
     * <p>
     * The body is repeated here instead of calling the guard because the guard reports on the thread that runs
     * the entity body, which is the region worker this record was made to keep the report off. It has to stay
     * identical to the guard: the same report title, the same category, and the same
     * {@code removeErroringEntities} branch, so the crash report, the log line and the shutdown of a broken
     * entity tick are the ones vanilla produces.
     */
    private static void reportEntityTickFailure(Entity entity, Throwable throwable) {
        final CrashReport report = CrashReport.forThrowable(throwable, "Ticking entity");
        final CrashReportCategory category = report.addCategory("Entity being ticked");
        entity.fillCrashReportCategory(category);
        if (removeErroringEntities()) {
            LOGGER.error("{}", report.getFriendlyReport(ReportType.CRASH));
            entity.discard();
            return;
        }
        throw new ReportedException(report);
    }

    /**
     * {@return the configured {@code removeErroringEntities} value, or its declared default before the server
     * config is loaded}
     */
    private static boolean removeErroringEntities() {
        return NeoForgeConfig.isServerConfigLoaded()
                ? NeoForgeConfig.SERVER.removeErroringEntities.get()
                : NeoForgeConfig.SERVER.removeErroringEntities.getDefault();
    }

    private static void rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Region worker tick failed", throwable);
    }
}
