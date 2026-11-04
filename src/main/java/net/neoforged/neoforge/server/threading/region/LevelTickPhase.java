/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import org.jetbrains.annotations.ApiStatus;

/**
 * The phase of a level tick that the server thread is executing.
 * <p>
 * A level tick is the vanilla {@code ServerLevel.tick} body, opened on the server thread at the head of the
 * level's tick and closed at its tail. Vanilla interleaves level-global statements (world border, weather, time,
 * sleep, raids, the chunk source, the block events and the entity management) with the slices a region may
 * execute (scheduled ticks, natural spawning, chunk ticks, entity ticks and block-entity ticks). The slices keep
 * exactly their vanilla position, so the tick is a sequence of alternating segments:
 * <ol>
 * <li>{@link #GLOBAL}: the server thread executes level-global statements. No region batch may be in flight: no
 * worker is running and no region lease is held.</li>
 * <li>{@link #REGION}: the server thread has handed a slice to the region workers and waits at the batch
 * barrier. Exactly one batch of the level is in flight, so two region slices of one level never overlap.</li>
 * </ol>
 * The phases are explicit state, not a convention: {@link LevelTickPhaseState} tracks them, the transitions are
 * counted in {@link RegionThreadingDiagnostics}, and entering a region phase while a global segment runs is a
 * reported ownership violation. The first global segment of a tick also drains the level's global mailbox
 * ({@link RegionMailbox}); every later global segment is the point at which a global message a worker posted is
 * delivered, so a global message is applied on the server thread before the vanilla body continues.
 * <h2>Ownership table</h2>
 * The global phase owns the level state that exists once per level. A region phase owns only its regions and
 * may <em>read</em> this state; a write is an ownership violation. The table lists what is owned, who writes it
 * today, and how the rule is enforced:
 * <table>
 * <caption>state owned by the global phase</caption>
 * <tr>
 * <th>state</th>
 * <th>written by</th>
 * <th>enforcement</th>
 * </tr>
 * <tr>
 * <td>time: game time, day time, day time fraction, day time per tick</td>
 * <td>{@code ServerLevel.tickTime}, {@code ServerLevel.setDayTime}, {@code ServerLevel.setDayTimeFraction},
 * {@code ServerLevel.setDayTimePerTick}</td>
 * <td>asserted: the four accessors call
 * {@code RegionThreadingHooks.assertGlobalStateWrite}, which rejects them in a region phase</td>
 * </tr>
 * <tr>
 * <td>weather: rain and thunder time and level, the weather cycle</td>
 * <td>{@code ServerLevel.advanceWeatherCycle} and {@code ServerLevel.setWeatherParameters}</td>
 * <td>asserted in {@code setWeatherParameters}; the cycle itself runs in the global segment it is written from
 * </td>
 * </tr>
 * <tr>
 * <td>game rules</td>
 * <td>{@code GameRules.Value#set} and the per-rule setters</td>
 * <td>documented only: every rule is its own value object, so there is no single accessor to assert</td>
 * </tr>
 * <tr>
 * <td>world border</td>
 * <td>{@code WorldBorder#setSize}, {@code setCenter}, {@code setWarningBlocks}, {@code setDamagePerBlock} and
 * their {@code *Time} siblings</td>
 * <td>documented only: the border is one object shared by the dimensions and has several accessors</td>
 * </tr>
 * <tr>
 * <td>scoreboard</td>
 * <td>{@code ServerScoreboard}: objectives, teams, scores and their display slots</td>
 * <td>documented only: the mutators are per objective and per team, so there is no single accessor</td>
 * </tr>
 * <tr>
 * <td>player list and the network state derived from it</td>
 * <td>{@code PlayerList#add}/{@code #remove}, {@code ChunkMap} tracking and the connection tick</td>
 * <td>documented only: add and remove are two accessors and the connection tick owns the queues between
 * them</td>
 * </tr>
 * </table>
 * A write of the asserted state from a native region worker is rejected as well: a worker is always inside a
 * region phase, because it only ever executes a leased batch group.
 */
@ApiStatus.Internal
public enum LevelTickPhase {
    /**
     * The server thread executes level-global statements; no region batch is in flight.
     */
    GLOBAL,
    /**
     * The server thread waits at the barrier of one region slice batch; exactly one batch of the level is in
     * flight and its workers may be running.
     */
    REGION
}
