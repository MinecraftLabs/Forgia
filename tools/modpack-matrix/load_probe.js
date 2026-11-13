// Copied only into the isolated load-test instance; not installed into the source pack.
// A fake player exercises player-dependent spawn/chunk gates. It is not a network client.
var FLPos = Java.loadClass('net.minecraft.core.BlockPos');
var FLChunk = Java.loadClass('net.minecraft.world.level.ChunkPos');
var FLTicket = Java.loadClass('net.minecraft.server.level.TicketType');
var FLFake = Java.loadClass('net.neoforged.neoforge.common.util.FakePlayerFactory');
var FLProfile = Java.loadClass('com.mojang.authlib.GameProfile');
var FLUUID = Java.loadClass('java.util.UUID');
var FLText = Java.loadClass('net.minecraft.network.chat.Component');
var FLGameType = Java.loadClass('net.minecraft.world.level.GameType');
var FLMemory = Java.loadClass('net.minecraft.world.entity.ai.memory.MemoryModuleType');
var flPlayer = null;
var flTickets = false;
var flContainers = [];
var flCrops = [];
var flLamp = new FLPos(1026, 281, 1024);
var flComparator = new FLPos(1024, 281, 1024);

function flReply(ctx, data) {
    ctx.source.sendSystemMessage(FLText.literal('FORGIA_PROBE ' + JSON.stringify(data)));
    return data.error ? 0 : 1;
}

function flStatus(level) {
    var loaded = 0;
    for (var x = 61; x <= 66; x++) {
        for (var z = 61; z <= 66; z++) {
            // A chunk can be present in the map but not yet at FULL status, which is what a fill/setblock needs.
            if (level.isLoaded(new FLPos(x * 16 + 8, 64, z * 16 + 8))) loaded++;
        }
    }
    var population = 0;
    var villagers = 0;
    var claimed = 0;
    var looseCobble = 0;
    var entities = level.getAllEntities().iterator();
    while (entities.hasNext()) {
        var entity = entities.next();
        if (entity.getTags().contains('forgia_load')) {
            population++;
            if (String(entity.getType()) === 'minecraft:villager') {
                villagers++;
                if (entity.getBrain().hasMemoryValue(FLMemory.HOME)
                        && entity.getBrain().hasMemoryValue(FLMemory.JOB_SITE)) claimed++;
            }
        }
        if (String(entity.getType()) === 'minecraft:item') {
            var item = entity.getItem();
            if (String(item.getItem()) === 'minecraft:cobblestone') looseCobble += item.getCount();
        }
    }
    var cobble = looseCobble;
    var glass = 0;
    var existing = 0;
    for (var i = 0; i < flContainers.length; i++) {
        var blockEntity = level.getBlockEntity(flContainers[i]);
        if (blockEntity === null) continue;
        existing++;
        for (var slot = 0; slot < blockEntity.getContainerSize(); slot++) {
            var stack = blockEntity.getItem(slot);
            if (String(stack.getItem()) === 'minecraft:cobblestone') cobble += stack.getCount();
            if (String(stack.getItem()) === 'minecraft:glass') glass += stack.getCount();
        }
    }
    var grown = 0;
    for (var j = 0; j < flCrops.length; j++) {
        var state = String(level.getBlockState(flCrops[j]));
        if (state.indexOf('minecraft:wheat') >= 0 && state.indexOf('age=0') < 0) grown++;
    }
    return {
        loadedChunks: loaded, expectedChunks: 36, fixtureMobs: population,
        villagers: villagers, villagersWithPois: claimed, containers: existing,
        expectedContainers: flContainers.length, cobblestone: cobble, glass: glass,
        grownCrops: grown, crops: flCrops.length, playerPresent: flPlayer !== null && !flPlayer.isRemoved(),
        lamp: String(level.getBlockState(flLamp)),
        comparator: String(level.getBlockState(flComparator))
    };
}

ServerEvents.commandRegistry(event => {
    var commands = event.commands;
    event.register(commands.literal('forgia_probe').requires(source => source.hasPermission(4))
        .then(commands.literal('tickets').executes(ctx => {
            try {
                var level = ctx.source.getLevel();
                if (flTickets) return flReply(ctx, {error: 'tickets already installed'});
                for (var x = 61; x <= 66; x++) {
                    for (var z = 61; z <= 66; z++) {
                        var pos = new FLChunk(x, z);
                        level.setChunkForced(x, z, true);
                        level.getChunkSource().addRegionTicket(FLTicket.FORCED, pos, 1, pos, true);
                    }
                }
                flTickets = true;
                return flReply(ctx, {tickets: 36});
            } catch (error) { return flReply(ctx, {error: String(error)}); }
        }))
        .then(commands.literal('player').executes(ctx => {
            try {
                if (flPlayer !== null) return flReply(ctx, {error: 'fake player already installed'});
                var level = ctx.source.getLevel();
                var profile = new FLProfile(FLUUID.fromString('0bd445dc-f92d-4f78-aec4-058c7afe0fa1'), 'ForgiaLoad');
                flPlayer = FLFake.get(level, profile);
                flPlayer.setPosition(1032.5, 286, 1032.5);
                flPlayer.setGameMode(FLGameType.CREATIVE);
                flPlayer.setNoGravity(true);
                level.addNewPlayer(flPlayer);
                level.getChunkSource().move(flPlayer);
                return flReply(ctx, {player: 'ForgiaLoad', present: !flPlayer.isRemoved()});
            } catch (error) { return flReply(ctx, {error: String(error)}); }
        }))
        .then(commands.literal('track').executes(ctx => {
            flContainers = [];
            flCrops = [];
            var pads = [[1000, 1000], [1048, 1000], [1000, 1048], [1048, 1048]];
            for (var i = 0; i < pads.length; i++) {
                var x = pads[i][0], z = pads[i][1];
                // Every container of a pad, including the hopper and furnace slots items sit in flight.
                for (var y = 281; y <= 284; y++) flContainers.push(new FLPos(x, y, z));
                for (var y2 = 281; y2 <= 284; y2++) flContainers.push(new FLPos(x + 2, y2, z));
                for (var dx = -4; dx < 0; dx++) {
                    for (var dz = 2; dz < 6; dz++) flCrops.push(new FLPos(x + dx, 281, z + dz));
                }
            }
            flContainers.push(new FLPos(1023, 281, 1024));
            return flReply(ctx, flStatus(ctx.source.getLevel()));
        }))
        .then(commands.literal('status').executes(ctx => {
            try { return flReply(ctx, flStatus(ctx.source.getLevel())); }
            catch (error) { return flReply(ctx, {error: String(error)}); }
        })));
});
