package io.github.jamalam360.rightclickharvest;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import io.github.jamalam360.jamlib.JamLib;
import io.github.jamalam360.jamlib.JamLibPlatform;
import io.github.jamalam360.jamlib.config.ConfigManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.ApiStatus.Internal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class RightClickHarvest {

    public static final String MOD_ID = "rightclickharvest";
    public static final String MOD_NAME = "Right Click Harvest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    public static final ConfigManager<Config> CONFIG = new ConfigManager<>(MOD_ID, Config.class);

    public static final TagKey<Block> BLACKLIST = TagKey.create(Registries.BLOCK, id("blacklist"));
    public static final TagKey<Block> HOE_NEVER_REQUIRED = TagKey.create(Registries.BLOCK, id("hoe_never_required"));
    public static final TagKey<Block> RADIUS_HARVEST_BLACKLIST = TagKey.create(Registries.BLOCK, id("radius_harvest_blacklist"));
    public static final TagKey<Item> LOW_TIER_HOES = TagKey.create(Registries.ITEM, id("low_tier_hoes"));
    public static final TagKey<Item> MID_TIER_HOES = TagKey.create(Registries.ITEM, id("mid_tier_hoes"));
    public static final TagKey<Item> HIGH_TIER_HOES = TagKey.create(Registries.ITEM, id("high_tier_hoes"));
    public static final Direction[] CARDINAL_DIRECTIONS = new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    public static void init() {
        LOGGER.info("Initializing Right Click Harvest on " + JamLibPlatform.getPlatform().name());
        JamLib.checkForJarRenaming(RightClickHarvest.class);

        InteractionEvent.RIGHT_CLICK_BLOCK.register(((player, hand, pos, face) -> {
            if (player.isSpectator() || player.isCrouching() || hand != InteractionHand.MAIN_HAND) {
                return EventResult.pass();
            }

            var hitResult = new BlockHitResult(player.position(), face, pos, false);
            var res = RightClickHarvest.onBlockUse(player, hitResult);

            return switch (res) {
                case SUCCESS -> EventResult.interruptTrue();
                case PASS -> EventResult.pass();
                case FAIL -> EventResult.interruptFalse();
                default -> throw new IllegalStateException("Unexpected value: " + res);
            };
        }));
    }

    @Internal
    public static InteractionResult onBlockUse(Player player, BlockHitResult hitResult) {
        return new Harvester(player, hitResult).harvest();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    static class Harvester extends BaseHarvester {
        Harvester(Player player, BlockHitResult hitResult) {
            super(player, hitResult);
        }

        InteractionResult harvest() {
            if (isHoeRequiredWithWarning()) return InteractionResult.PASS;
            if (cannotHarvest()) return InteractionResult.PASS;
            if (canRadiusHarvest()) radiusHarvest();
            return blockHarvest();
        }

        private boolean isHoeRequiredWithWarning() {
            // Check if the block requires a hoe; if so, check if a hoe is required and if the user has one.
            var required = !state.is(HOE_NEVER_REQUIRED) && CONFIG.get().requireHoe && !isHoeInHand() && isHarvestable();
            if (required) warnOnceForNotUsingHoe();
            return required;
        }

        private boolean isHarvestable() {
            return isReplantableAndMature() || isSugarCaneOrCactus();
        }

        private void warnOnceForNotUsingHoe() {
            if (CONFIG.get().hasUserBeenWarnedForNotUsingHoe || !level.isClientSide) return;

            var translatable = Component.translatable(
                    "text.rightclickharvest.use_a_hoe_warning",
                    Component.translatable("config.rightclickharvest.requireHoe").withStyle(s -> s.withColor(ChatFormatting.GREEN)),
                    Component.literal("false").withStyle(s -> s.withColor(ChatFormatting.GREEN)
                    ));
            player.displayClientMessage(translatable, false);

            CONFIG.get().hasUserBeenWarnedForNotUsingHoe = true;
            CONFIG.save();
        }

        private boolean canRadiusHarvest() {
            return CONFIG.get().harvestInRadius && !state.is(RADIUS_HARVEST_BLACKLIST) && isHoeInHand() && isReplantableAndMature();
        }

        private void radiusHarvest() {
            int radius = 0;
            boolean circle = false;

            var hoeInHand = stackInMainHand; // b/c radius harvesting is done only by a hoe
            if (hoeInHand.is(HIGH_TIER_HOES)) {
                radius = 2;
                circle = true;
            } else if (hoeInHand.is(MID_TIER_HOES)) {
                radius = 1;
                // circle = false;
            } else if (hoeInHand.is(LOW_TIER_HOES)) {
                radius = 1;
                circle = true;
            }

            if (radius == 1 && circle) {
                for (Direction dir : CARDINAL_DIRECTIONS) {
                    new RadiusHarvester(player, hitResult.withPosition(hitBlockPos.relative(dir))).harvest();
                }
            } else if (radius > 0) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x == 0 && z == 0) {
                            continue;
                        }

                        BlockPos pos = hitBlockPos.relative(Direction.Axis.X, x).relative(Direction.Axis.Z, z);
                        if (circle && pos.distManhattan(hitBlockPos) > radius) {
                            continue;
                        }

                        new RadiusHarvester(player, hitResult.withPosition(pos)).harvest();
                    }
                }
            }
        }
    }

    static class RadiusHarvester extends BaseHarvester {
        RadiusHarvester(Player player, BlockHitResult hitResult) {
            super(player, hitResult);
        }

        void harvest() {
            if (cannotRadiusHarvest()) return;
            blockHarvest();
        }

        private boolean cannotRadiusHarvest() {
            return state.is(RADIUS_HARVEST_BLACKLIST) && cannotHarvest();
        }
    }

    static class BaseHarvester {
        protected final Player player;
        protected final BlockHitResult hitResult;
        protected final Level level;
        protected final ItemStack stackInMainHand;
        protected final BlockState state;
        protected final BlockPos hitBlockPos;
        private final Block block;

        BaseHarvester(Player player, BlockHitResult hitResult) {
            this.player = player;
            this.hitResult = hitResult;

            this.level = player.level();
            this.stackInMainHand = player.getMainHandItem();
            this.hitBlockPos = hitResult.getBlockPos();
            this.state = level.getBlockState(hitBlockPos);
            this.block = state.getBlock();
        }

        protected boolean isHoeInHand() {
            return stackInMainHand.is(ItemTags.HOES)
                    || stackInMainHand.is(LOW_TIER_HOES)
                    || stackInMainHand.is(MID_TIER_HOES)
                    || stackInMainHand.is(HIGH_TIER_HOES)
                    || RightClickHarvestPlatform.isHoeAccordingToPlatform(stackInMainHand);
        }

        private boolean isReplantable() {
            return block instanceof CocoaBlock || block instanceof CropBlock || block instanceof NetherWartBlock;
        }

        protected boolean isSugarCaneOrCactus() {
            return block instanceof SugarCaneBlock || block instanceof CactusBlock;
        }

        protected boolean isReplantableAndMature() {
            return switch (block) {
                case CocoaBlock cocoaBlock -> state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
                case CropBlock cropBlock -> cropBlock.isMaxAge(state);
                case NetherWartBlock netherWartBlock -> state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
                default -> false;
            };
        }

        private BlockState getReplantState() {
            return switch (block) {
                case CocoaBlock cocoaBlock -> state.setValue(CocoaBlock.AGE, 0);
                case CropBlock cropBlock -> cropBlock.getStateForAge(0);
                case NetherWartBlock netherWartBlock -> state.setValue(NetherWartBlock.AGE, 0);
                default -> state;
            };
        }

        protected boolean cannotHarvest() {
            return state.is(BLACKLIST) || isExhausted();
        }

        // Check for hunger, if config requires it
        private boolean isExhausted() {
            if (player.hasInfiniteMaterials()) return false;
            if (CONFIG.get().hungerLevel != Config.HungerLevel.NONE) return false;
            return player.getFoodData().getFoodLevel() <= 0;
        }

        protected InteractionResult blockHarvest() {
            if (isReplantableAndMature()) return completeHarvest();
            if (isSugarCaneOrCactus()) return harvestSugarCaneOrCactus();

            return InteractionResult.PASS;
        }

        private InteractionResult harvestSugarCaneOrCactus() {
            var itemInHand = stackInMainHand.getItem();
            var isSugarCane = itemInHand == Items.SUGAR_CANE && block instanceof SugarCaneBlock;
            var isCactus = itemInHand == Items.CACTUS && block instanceof CactusBlock;
            if (hitResult.getDirection() == Direction.UP && (isSugarCane || isCactus)) {
                return InteractionResult.PASS;
            }

            var lookingFor = block;
            var bottom = hitBlockPos;
            while (level.getBlockState(bottom.below()).is(lookingFor)) {
                bottom = bottom.below();
            }

            // Only one block tall
            if (!level.getBlockState(bottom.above()).is(lookingFor)) {
                return InteractionResult.PASS;
            }

            var breakPos = bottom.above(1);
            return completeHarvest(breakPos);
        }

        private InteractionResult completeHarvest() {
            return completeHarvest(hitBlockPos);
        }

        private InteractionResult completeHarvest(BlockPos pos) {
            if (level.isClientSide) return playSoundClientSide();

            // ==== Server Side only below ====

            // Event posts are for things like claim mods
            if (RightClickHarvestPlatform.postBreakEvent(pos, state, player)) return InteractionResult.FAIL;
            if (RightClickHarvestPlatform.postPlaceEvent(pos, player)) return InteractionResult.FAIL;

            dropStacks(pos);

            if (isReplantableAndMature()) level.setBlockAndUpdate(pos, getReplantState());
            else if (isSugarCaneOrCactus()) level.removeBlock(pos, false);

            wearHoeInHand();

            // Regular block breaking causes 0.005f exhaustion
            player.causeFoodExhaustion(0.008f * CONFIG.get().hungerLevel.modifier);

            RightClickHarvestPlatform.postAfterHarvestEvent(player, block); // block is the original one here

            return InteractionResult.SUCCESS;
        }

        private InteractionResult playSoundClientSide() {
            var soundEvent = block instanceof NetherWartBlock ? SoundEvents.NETHER_WART_PLANTED : SoundEvents.CROP_PLANTED;
            player.playSound(soundEvent, 1.0f, 1.0f);
            return InteractionResult.SUCCESS;
        }

        private void wearHoeInHand() {
            if (isHoeInHand()) stackInMainHand.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        }

        private void dropStacks(BlockPos pos) {
            ServerLevel world = (ServerLevel) level;
            List<ItemStack> drops = Block.getDrops(state, world, pos, null, player, stackInMainHand);
            boolean needToReplant = isReplantable();
            Item replant = block.asItem();

            for (ItemStack droppedStack : drops) {
                if (needToReplant && droppedStack.is(replant)) {
                    droppedStack.shrink(1);
                    needToReplant = false;
                }

                Block.popResource(world, pos, droppedStack);
            }
        }
    }
}
