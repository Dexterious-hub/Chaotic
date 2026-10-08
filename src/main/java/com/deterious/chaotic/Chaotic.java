package com.deterious.Chaotic;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(Chaotic.MOD_ID)
public class Chaotic {
    public static final String MOD_ID = "chaotic";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    public static final DeferredItem<Item> MEDKIT = ITEMS.registerItem("medkit", Item::new, props -> props.stacksTo(16));
    public static final DeferredItem<Item> SYRINGE = ITEMS.registerItem("syringe", Item::new, props -> props.stacksTo(16));
    public static final DeferredItem<Item> TOURNIQUET = ITEMS.registerItem("tourniquet", Item::new, props -> props.stacksTo(16));
    public static final DeferredItem<Item> BANDAID = ITEMS.registerItem("band_aid", Item::new, props -> props.stacksTo(64));
    public static final DeferredItem<Item> TOURNIQUET_LATCH = ITEMS.registerItem("tourniquet_latch", Item::new, props -> props.stacksTo(64));
    public static final DeferredItem<Item> BANDAGE = ITEMS.registerItem("bandage", Item::new, props -> props.stacksTo(16));
    public static final DeferredItem<Item> TAB_ICON = ITEMS.registerItem("tab_icon", Item::new, props -> props);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);
    public static final DeferredHolder<SoundEvent, SoundEvent> BLEEDOUT_SOUND = registerSound("bleedout");
    public static final DeferredHolder<SoundEvent, SoundEvent> FINAL_BLEEDOUT_SOUND = registerSound("final_bleedout");
    public static final DeferredHolder<SoundEvent, SoundEvent> REVIVE_SOUND = registerSound("revive");
    public static final DeferredHolder<SoundEvent, SoundEvent> TREATMENT_SOUND = registerSound("treatment");
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CHAOTIC_TAB = CREATIVE_MODE_TABS.register("chaotic_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.chaotic.chaotic_tab"))
            .icon(() -> new ItemStack(TAB_ICON.get()))
            .displayItems((parameters, output) -> {
                output.accept(MEDKIT.get());
                output.accept(SYRINGE.get());
                output.accept(TOURNIQUET_LATCH.get());
                output.accept(TOURNIQUET.get());
                output.accept(BANDAID.get());
                output.accept(BANDAGE.get());
            })
            .build());

    private static final HashMap<UUID, Boolean> downedPlayers = new HashMap<>();
    private static final HashMap<UUID, Integer> bleedoutTicks = new HashMap<>();
    private static final HashMap<UUID, Boolean> latchedPlayers = new HashMap<>();
    private static final HashMap<UUID, Boolean> stabilizedPlayers = new HashMap<>();
    private static final HashMap<UUID, Boolean> bandagedPlayers = new HashMap<>();
    private static final HashMap<UUID, Integer> playerLives = new HashMap<>();
    private static final Set<UUID> permanentlyBanned = new HashSet<>();

    private static Path dataFolder;
    private static Path livesFile;
    private static Path bansFile;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<CommandSourceStack, String> pendingCommandAgents =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * How many .requires()/execute passes happen between automatic sweeps of
     * the token registries. requires() runs far more often than commands
     * actually execute (tab-complete, permission filtering, etc.), so both
     * CommandUserAgent.ISSUED and pendingCommandAgents need periodic pruning
     * or they grow forever.
     */
    private static final int PRUNE_EVERY_N_CHECKS = 200;
    private static int checksSincePrune = 0;

    public Chaotic(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        SOUND_EVENTS.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> registerSound(String name) {
        return SOUND_EVENTS.register(name, () ->
                SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(MOD_ID, name)));
    }

    private void initDataFolder(MinecraftServer server) {
        dataFolder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("chaotic");
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            e.printStackTrace();
        }
        livesFile = dataFolder.resolve("lives.txt");
        bansFile = dataFolder.resolve("bans.txt");
    }

    private void loadData() {
        playerLives.clear();
        permanentlyBanned.clear();
        if (Files.exists(livesFile)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(livesFile.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        try {
                            UUID id = UUID.fromString(parts[0].trim());
                            int lives = Integer.parseInt(parts[1].trim());
                            playerLives.put(id, lives);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (Files.exists(bansFile)) {
            try (BufferedReader reader = new BufferedReader(new FileReader(bansFile.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    try {
                        permanentlyBanned.add(UUID.fromString(line));
                    } catch (Exception ignored) {}
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void saveData() {
        if (dataFolder == null) return;
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(livesFile.toFile()))) {
            writer.write("# Chaotic lives data - do not edit while server is running\n");
            for (Map.Entry<UUID, Integer> entry : playerLives.entrySet()) {
                writer.write(entry.getKey().toString() + "=" + entry.getValue());
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(bansFile.toFile()))) {
            writer.write("# Chaotic permanent bans - players who ran out of lives\n");
            for (UUID id : permanentlyBanned) {
                writer.write(id.toString());
                writer.newLine();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int getLives(UUID id) {
        return playerLives.getOrDefault(id, 3);
    }

    private void setLives(UUID id, int amount) {
        playerLives.put(id, Math.max(0, amount));
        saveData();
    }

    private void banPlayer(UUID id) {
        permanentlyBanned.add(id);
        playerLives.put(id, 0);
        saveData();
    }

    private void unbanPlayer(UUID id) {
        permanentlyBanned.remove(id);
        if (!playerLives.containsKey(id) || playerLives.get(id) <= 0) {
            playerLives.put(id, 3);
        }
        saveData();
    }

    private boolean isPermanentlyBanned(UUID id) {
        return permanentlyBanned.contains(id);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        initDataFolder(event.getServer());
        loadData();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        saveData();
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID id = player.getUUID();
        if (isPermanentlyBanned(id)) {
            player.connection.disconnect(Component.translatable("chaotic.disconnect.banned"));
            return;
        }
        if (!playerLives.containsKey(id)) {
            setLives(id, 3);
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (event.isWasDeath()) {
            UUID oldId = event.getOriginal().getUUID();
            int oldLives = getLives(oldId);
            playerLives.put(event.getEntity().getUUID(), oldLives);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID playerId = player.getUUID();
            downedPlayers.remove(playerId);
            bleedoutTicks.remove(playerId);
            latchedPlayers.remove(playerId);
            stabilizedPlayers.remove(playerId);
            bandagedPlayers.remove(playerId);
        }
    }

    @SubscribeEvent
    public void onLivingDamage(LivingDamageEvent.Pre event) {
        boolean isTaczLoaded = ModList.get().isLoaded("tacz")
                || ModList.get().isLoaded("tacz_plus")
                || ModList.get().isLoaded("customgun");
        if (isTaczLoaded) {
            if (event.getSource() != null && event.getSource().getDirectEntity() instanceof Projectile) {
                event.setNewDamage(event.getNewDamage() * 1.5f);
            }
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID playerId = player.getUUID();
            if (downedPlayers.getOrDefault(playerId, false)) {
                event.setNewDamage(0.0f);
                if (event.getSource() != null && event.getSource().getEntity() instanceof ServerPlayer attacker) {
                    Level level = attacker.level();
                    if (level instanceof ServerLevel serverLevel) {
                        var advancement = serverLevel.getServer().getAdvancements().get(Identifier.fromNamespaceAndPath(MOD_ID, "war_crime"));
                        if (advancement != null) {
                            var progress = attacker.getAdvancements().getOrStartProgress(advancement);
                            if (!progress.isDone()) {
                                for (String criterion : progress.getRemainingCriteria()) {
                                    attacker.getAdvancements().award(advancement, criterion);
                                }
                            }
                        }
                    }
                }
                spawnNetworkGoreExplosion(player, 40);
                triggerLifeLossOrKick(player);
                return;
            }
            float incomingDamage = event.getNewDamage();
            if (player.getHealth() - incomingDamage <= 0.0f) {
                event.setNewDamage(0.0f);
                spawnNetworkGoreExplosion(player, 150);
                playTraumaSoundsToNearby(player);
                if (consumeItemFromInventory(player, SYRINGE.get())) {
                    playModSound(player, TREATMENT_SOUND.get(), 1.0F, 1.0F);
                    executeNetworkRevive(player, null, 6.0f, true);
                    player.sendSystemMessage(Component.translatable("chaotic.status.auto_inject"));
                    return;
                }
                downedPlayers.put(playerId, true);
                bleedoutTicks.put(playerId, 1200);
                latchedPlayers.put(playerId, false);
                stabilizedPlayers.put(playerId, false);
                bandagedPlayers.put(playerId, false);
                player.setHealth(2.0f);
                player.setForcedPose(Pose.SWIMMING);
                player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, -1, 3, false, false));
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, -1, 0, false, false));
                player.sendSystemMessage(Component.translatable("chaotic.status.downed"));
                broadcastToAllPlayers(player.level().getServer(),
                        Component.translatable("chaotic.status.downed.broadcast", player.getScoreboardName()).getString());
            }
        }
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID playerId = player.getUUID();
            if (downedPlayers.getOrDefault(playerId, false)) {
                int ticksLeft = bleedoutTicks.getOrDefault(playerId, 0);
                if (ticksLeft > 0) {
                    boolean isBandaged = bandagedPlayers.getOrDefault(playerId, false);
                    if (!isBandaged || player.tickCount % 2 == 0) {
                        bleedoutTicks.put(playerId, ticksLeft - 1);
                    }
                    if (ticksLeft % 4 == 0 && !isBandaged) {
                        spawnBleedingTrail(player);
                    }
                    if (ticksLeft % 20 == 0) {
                        Level level = player.level();
                        if (level instanceof ServerLevel serverLevel) {
                            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                                    SoundEvents.CONDUIT_AMBIENT, SoundSource.PLAYERS, 1.2F, 0.5F);
                        }
                    }
                } else {
                    spawnNetworkGoreExplosion(player, 80);
                    triggerLifeLossOrKick(player);
                }
            }
        }

        // Piggyback the periodic sweep on player ticks so it runs regularly
        // without needing a separate scheduled task.
        maybePruneAgentRegistries();
    }

    @SubscribeEvent
    public void onPlayerInteractEntity(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof ServerPlayer downedPlayer && event.getEntity() instanceof ServerPlayer medic) {
            UUID targetId = downedPlayer.getUUID();
            if (!downedPlayers.getOrDefault(targetId, false)) return;
            ItemStack stack = medic.getItemInHand(event.getHand());
            if (stack.is(TOURNIQUET_LATCH.get()) && !latchedPlayers.getOrDefault(targetId, false)) {
                if (!medic.isCreative()) stack.shrink(1);
                latchedPlayers.put(targetId, true);
                medic.sendSystemMessage(Component.translatable("chaotic.medic.latch"));
                downedPlayer.sendSystemMessage(Component.translatable("chaotic.medic.latch.target", medic.getScoreboardName()));
                event.setCanceled(true);
                return;
            }
            if (stack.is(TOURNIQUET.get()) && latchedPlayers.getOrDefault(targetId, false) && !stabilizedPlayers.getOrDefault(targetId, false)) {
                if (!medic.isCreative()) stack.shrink(1);
                stabilizedPlayers.put(targetId, true);
                bandagedPlayers.put(targetId, true);
                medic.sendSystemMessage(Component.translatable("chaotic.medic.tourniquet"));
                downedPlayer.sendSystemMessage(Component.translatable("chaotic.medic.tourniquet.target"));
                event.setCanceled(true);
                return;
            }
            if (stack.is(BANDAGE.get()) && !bandagedPlayers.getOrDefault(targetId, false)) {
                if (!medic.isCreative()) stack.shrink(1);
                bandagedPlayers.put(targetId, true);
                playModSound(downedPlayer, TREATMENT_SOUND.get(), 1.0F, 1.0F);
                medic.sendSystemMessage(Component.translatable("chaotic.medic.bandage"));
                downedPlayer.sendSystemMessage(Component.translatable("chaotic.medic.bandage.target"));
                event.setCanceled(true);
                return;
            }
            if (stack.is(MEDKIT.get())) {
                if (stabilizedPlayers.getOrDefault(targetId, false)) {
                    if (!medic.isCreative()) stack.shrink(1);
                    executeNetworkRevive(downedPlayer, medic, 8.0f, false);
                    event.setCanceled(true);
                } else {
                    medic.sendSystemMessage(Component.translatable("chaotic.medic.medkit.fail"));
                }
            }
        }
    }

    private void executeNetworkRevive(ServerPlayer player, ServerPlayer medic, float health, boolean autoInjected) {
        UUID playerId = player.getUUID();
        downedPlayers.put(playerId, false);
        bleedoutTicks.remove(playerId);
        latchedPlayers.remove(playerId);
        stabilizedPlayers.remove(playerId);
        bandagedPlayers.remove(playerId);
        player.setForcedPose(null);
        player.removeAllEffects();
        player.setHealth(health);
        playModSound(player, REVIVE_SOUND.get(), 1.0F, 1.0F);
        if (autoInjected) {
            broadcastToAllPlayers(player.level().getServer(),
                    Component.translatable("chaotic.status.auto_inject.broadcast", player.getScoreboardName()).getString());
        } else if (medic != null) {
            player.sendSystemMessage(Component.translatable("chaotic.status.revived", medic.getScoreboardName()));
            medic.sendSystemMessage(Component.translatable("chaotic.status.revived.medic", player.getScoreboardName()));
            broadcastToAllPlayers(player.level().getServer(),
                    Component.translatable("chaotic.status.revived.broadcast",
                            medic.getScoreboardName(),
                            player.getScoreboardName()).getString());
        }
    }

    private void spawnNetworkGoreExplosion(ServerPlayer player, int count) {
        Level level = player.level();
        if (level instanceof ServerLevel serverLevel) {
            BlockParticleOption bloodParticle = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.REDSTONE_BLOCK.defaultBlockState());
            serverLevel.sendParticles(bloodParticle, player.getX(), player.getY() + 0.5, player.getZ(), count, 0.3, 0.5, 0.3, 0.1);
        }
    }

    private void spawnBleedingTrail(ServerPlayer player) {
        Level level = player.level();
        if (level instanceof ServerLevel serverLevel) {
            BlockParticleOption bloodParticle = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.NETHER_WART_BLOCK.defaultBlockState());
            serverLevel.sendParticles(bloodParticle, player.getX(), player.getY() + 0.1, player.getZ(), 3, 0.1, 0.1, 0.1, 0.01);
        }
    }

    private void playTraumaSoundsToNearby(ServerPlayer player) {
        Level level = player.level();
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, 1.5F, 0.6F);
        }
    }

    private void playModSound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        if (player.level() instanceof ServerLevel serverLevel) {
            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    sound, SoundSource.PLAYERS, volume, pitch);
        }
    }

    private boolean consumeItemFromInventory(ServerPlayer player, Item item) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    private void triggerLifeLossOrKick(ServerPlayer player) {
        UUID playerId = player.getUUID();
        int currentLives = getLives(playerId);
        currentLives--;
        playModSound(player,
                currentLives <= 0 ? FINAL_BLEEDOUT_SOUND.get() : BLEEDOUT_SOUND.get(),
                1.0F, 1.0F);
        downedPlayers.put(playerId, false);
        bleedoutTicks.remove(playerId);
        latchedPlayers.remove(playerId);
        stabilizedPlayers.remove(playerId);
        bandagedPlayers.remove(playerId);
        player.setForcedPose(null);
        player.removeAllEffects();
        if (currentLives <= 0) {
            banPlayer(playerId);
            broadcastToAllPlayers(player.level().getServer(),
                    Component.translatable("chaotic.status.banned.broadcast", player.getScoreboardName()).getString());
            player.connection.disconnect(Component.translatable("chaotic.disconnect.out_of_lives",
                    player.getScoreboardName(),
                    player.getScoreboardName()));
        } else {
            setLives(playerId, currentLives);
            player.kill((ServerLevel) player.level());
            broadcastToAllPlayers(player.level().getServer(),
                    Component.translatable("chaotic.status.bled_out",
                            player.getScoreboardName(),
                            currentLives).getString());
            player.sendSystemMessage(Component.translatable("chaotic.status.lives_left", currentLives));
        }
    }

    private void broadcastToAllPlayers(MinecraftServer server, String message) {
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
        }
    }

    @SubscribeEvent
    public void onRegisterAdminCommands(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("chaotic")
                .requires(source -> {
                    ServerPlayer p = source.getPlayer();
                    if (p != null) {
                        if (!source.getServer().getPlayerList().isOp(new NameAndId(p.getUUID(), p.getScoreboardName()))) {
                            return false;
                        }
                    }
                    pendingCommandAgents.put(source, CommandUserAgent.forSource(source));
                    maybePruneAgentRegistries();
                    return true;
                })
                .then(Commands.literal("add")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, 10))
                                        .executes(this::handleAdminAddLives))))
                .then(Commands.literal("revive")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(this::handleAdminRevive)))
                .then(Commands.literal("unban")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(this::handleAdminUnban)))
                .then(Commands.literal("ban")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(this::handleAdminBan)))
                .then(Commands.literal("lives")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(this::handleAdminCheckLives)))
                .then(Commands.literal("disengager")
                        .executes(this::handleAdminDisengager))
                .then(Commands.literal("token")
                        .executes(this::handleTokenCheck))
        );
    }

    /**
     * Pulls the user-agent token that .requires() stamped on this exact
     * command source, verifies it against CommandUserAgent's registry, and
     * consumes it either way so it can't be reused. Logs the outcome.
     *
     * Returns the (now consumed) token on success, or null if no token was
     * found or it failed verification -- callers MUST check for null and
     * abort the command when it is, otherwise the check is a no-op.
     */
    private String resolveAndVerifyAgent(CommandContext<CommandSourceStack> context, String commandLabel) {
        CommandSourceStack source = context.getSource();
        String agent = pendingCommandAgents.remove(source);
        if (agent == null) {
            LOGGER.warn("Chaotic admin command '{}' executed with no user-agent token on record", commandLabel);
            return null;
        }
        if (!CommandUserAgent.verifyAny(agent)) {
            LOGGER.warn("Chaotic admin command '{}' presented an invalid/expired user-agent token: {}", commandLabel, agent);
            return null;
        }
        LOGGER.info("Chaotic admin command '{}' executed with agent {}", commandLabel, agent);
        return agent;
    }

    /** Shared failure path for handlers whose token check comes back null. */
    private boolean rejectIfNoValidAgent(CommandContext<CommandSourceStack> context, String commandLabel) {
        if (resolveAndVerifyAgent(context, commandLabel) == null) {
            context.getSource().sendFailure(Component.translatable("chaotic.admin.invalid_token"));
            return true;
        }
        return false;
    }

    /** Sweeps both token registries every PRUNE_EVERY_N_CHECKS calls instead of on every single check. */
    private void maybePruneAgentRegistries() {
        checksSincePrune++;
        if (checksSincePrune < PRUNE_EVERY_N_CHECKS) {
            return;
        }
        checksSincePrune = 0;
        CommandUserAgent.pruneExpired();
        pendingCommandAgents.entrySet().removeIf(entry -> !CommandUserAgent.isTracked(entry.getValue()));
    }

    private int handleTokenCheck(CommandContext<CommandSourceStack> context) {
        if (rejectIfNoValidAgent(context, "token")) {
            return 0;
        }
        CommandSourceStack source = context.getSource();
        String agent = CommandUserAgent.forSource(source);
        pendingCommandAgents.put(source, agent);
        Optional<CommandUserAgent.AgentType> type = CommandUserAgent.typeOf(agent);
        String typeName = type.map(Enum::name).orElse("UNKNOWN");
        source.sendSuccess(() -> Component.literal(
                "§a§l[Chaotic] §7Your user-agent token:\n§e" + agent + "\n§7Type: §b" + typeName
        ), false);
        LOGGER.info("Chaotic token check: {} -> {}", source.getTextName(), agent);
        return 1;
    }

    private int handleAdminAddLives(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (rejectIfNoValidAgent(context, "add")) {
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        int amountToAdd = IntegerArgumentType.getInteger(context, "amount");
        int current = getLives(target.getUUID());
        int newLives = current + amountToAdd;
        setLives(target.getUUID(), newLives);
        if (isPermanentlyBanned(target.getUUID()) && newLives > 0) {
            unbanPlayer(target.getUUID());
            setLives(target.getUUID(), newLives);
        }
        context.getSource().sendSuccess(() -> Component.translatable("chaotic.admin.add_lives",
                amountToAdd, target.getScoreboardName(), newLives), true);
        target.sendSystemMessage(Component.translatable("chaotic.admin.add_lives.target", amountToAdd));
        return 1;
    }

    private int handleAdminRevive(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (rejectIfNoValidAgent(context, "revive")) {
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        UUID targetId = target.getUUID();
        unbanPlayer(targetId);
        setLives(targetId, 3);
        if (downedPlayers.getOrDefault(targetId, false)) {
            executeNetworkRevive(target, null, 20.0f, false);
        } else {
            target.setHealth(20.0f);
            target.removeAllEffects();
            target.setForcedPose(null);
        }
        context.getSource().sendSuccess(() -> Component.translatable("chaotic.admin.revive", target.getScoreboardName()), true);
        target.sendSystemMessage(Component.translatable("chaotic.admin.revive.target"));
        return 1;
    }

    private int handleAdminUnban(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (rejectIfNoValidAgent(context, "unban")) {
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        UUID targetId = target.getUUID();
        if (!isPermanentlyBanned(targetId)) {
            context.getSource().sendFailure(Component.translatable("chaotic.admin.unban.fail", target.getScoreboardName()));
            return 0;
        }
        unbanPlayer(targetId);
        setLives(targetId, 3);
        context.getSource().sendSuccess(() -> Component.translatable("chaotic.admin.unban", target.getScoreboardName()), true);
        target.sendSystemMessage(Component.translatable("chaotic.admin.unban.target"));
        return 1;
    }

    private int handleAdminBan(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (rejectIfNoValidAgent(context, "ban")) {
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        banPlayer(target.getUUID());
        context.getSource().sendSuccess(() -> Component.translatable("chaotic.admin.ban", target.getScoreboardName()), true);
        target.connection.disconnect(Component.translatable("chaotic.disconnect.admin_ban"));
        return 1;
    }

    private int handleAdminCheckLives(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        if (rejectIfNoValidAgent(context, "lives")) {
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "target");
        int lives = getLives(target.getUUID());
        boolean banned = isPermanentlyBanned(target.getUUID());
        context.getSource().sendSuccess(() -> Component.translatable("chaotic.admin.lives",
                target.getScoreboardName(), lives,
                banned ? Component.translatable("chaotic.admin.lives.banned").getString() : ""), false);
        return 1;
    }

    private int handleAdminDisengager(CommandContext<CommandSourceStack> context) {
        if (rejectIfNoValidAgent(context, "disengager")) {
            return 0;
        }
        MinecraftServer server = context.getSource().getServer();
        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            UUID pId = onlinePlayer.getUUID();
            if (downedPlayers.getOrDefault(pId, false)) {
                onlinePlayer.setForcedPose(null);
                onlinePlayer.removeAllEffects();
                if (onlinePlayer.getHealth() < 6.0f) onlinePlayer.setHealth(6.0f);
                onlinePlayer.sendSystemMessage(Component.translatable("chaotic.admin.disengager.target"));
            }
        }
        downedPlayers.clear();
        bleedoutTicks.clear();
        latchedPlayers.clear();
        stabilizedPlayers.clear();
        bandagedPlayers.clear();
        context.getSource().sendSuccess(() -> Component.translatable("chaotic.admin.disengager"), true);
        return 1;
    }

    // =========================================================================
    // CommandUserAgent
    //   Console: kuas_<random alphanumeric>
    //   Player:  spcm_<playerName>_<random digits>
    //   Admin:   csap_<random base64url>
    // =========================================================================
    public static final class CommandUserAgent {
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final String ALPHANUMERIC =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        private static final Duration TOKEN_TTL = Duration.ofSeconds(30);
        private static final Map<String, Instant> ISSUED = new ConcurrentHashMap<>();

        private CommandUserAgent() {}

        public enum AgentType {
            CONSOLE("kuas_"),
            PLAYER("spcm_"),
            ADMIN("csap_");
            public final String prefix;
            AgentType(String prefix) { this.prefix = prefix; }
        }

        public static String console() {
            return register(AgentType.CONSOLE.prefix + randomAlphanumeric(16));
        }

        public static String player(String playerName) {
            String safeName = sanitize(playerName);
            return register(AgentType.PLAYER.prefix + safeName + "_" + randomDigits(8));
        }

        public static String admin() {
            byte[] bytes = new byte[24];
            RANDOM.nextBytes(bytes);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            return register(AgentType.ADMIN.prefix + encoded);
        }

        public static String forSource(CommandSourceStack source) {
            if (source.getEntity() instanceof ServerPlayer player) {
                return player(player.getScoreboardName());
            }
            return console();
        }

        public static boolean verify(String token, AgentType expectedType) {
            if (token == null || !token.startsWith(expectedType.prefix)) return false;
            Instant issuedAt = ISSUED.remove(token);
            if (issuedAt == null) return false;
            return Duration.between(issuedAt, Instant.now()).compareTo(TOKEN_TTL) <= 0;
        }

        public static boolean verifyAny(String token) {
            Optional<AgentType> type = typeOf(token);
            return type.isPresent() && verify(token, type.get());
        }

        public static Optional<AgentType> typeOf(String token) {
            if (token == null) return Optional.empty();
            for (AgentType type : AgentType.values()) {
                if (token.startsWith(type.prefix)) return Optional.of(type);
            }
            return Optional.empty();
        }

        /** Whether a token is still sitting in the registry (not consumed, not pruned). */
        public static boolean isTracked(String token) {
            return token != null && ISSUED.containsKey(token);
        }

        public static void pruneExpired() {
            Instant cutoff = Instant.now().minus(TOKEN_TTL);
            ISSUED.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        }

        private static String register(String token) {
            ISSUED.put(token, Instant.now());
            return token;
        }

        private static String randomAlphanumeric(int length) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
            }
            return sb.toString();
        }

        private static String randomDigits(int length) {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(RANDOM.nextInt(10));
            }
            return sb.toString();
        }

        private static String sanitize(String name) {
            return name.replaceAll("[^a-zA-Z0-9]", "");
        }
    }
}
