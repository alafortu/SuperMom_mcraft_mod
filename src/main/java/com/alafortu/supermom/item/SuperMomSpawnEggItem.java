package com.alafortu.supermom.item;

import com.alafortu.supermom.entity.SuperMomEntity;
import net.minecraft.core.BlockSource;
import net.minecraft.core.Direction;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public class SuperMomSpawnEggItem extends ForgeSpawnEggItem {

    public SuperMomSpawnEggItem(Supplier<? extends EntityType<? extends SuperMomEntity>> typeSupplier, int backgroundColor, int highlightColor, Properties props) {
        super(typeSupplier, backgroundColor, highlightColor, props);
        // Register dispenser behavior if needed (optional, good practice)
        DispenserBlock.registerBehavior(this, new DispenseItemBehavior() {
            @Override
            public ItemStack dispense(BlockSource source, ItemStack stack) {
                Direction direction = source.getBlockState().getValue(DispenserBlock.FACING);
                EntityType<?> entitytype = ((ForgeSpawnEggItem) stack.getItem()).getType(stack.getTag());
                // Spawn with owner set to null initially for dispenser
                entitytype.spawn(source.getLevel(), stack, null, source.getPos().relative(direction), MobSpawnType.DISPENSER, direction != Direction.UP, false);
                stack.shrink(1);
                return stack;
            }
        });
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel)) {
            return InteractionResult.SUCCESS; // Client side does nothing tangible
        } else {
            ItemStack itemstack = context.getItemInHand();
            BlockHitResult blockhitresult = (BlockHitResult) context.getPlayer().pick(5.0D, 0.0F, false); // Raycast to find spawn pos
            EntityType<?> entitytype = this.getType(itemstack.getTag());

            // Spawn the entity using the standard spawn method first
            // Pass the player using the egg as the 'spawner' argument
            SuperMomEntity spawnedEntity = (SuperMomEntity) entitytype.spawn((ServerLevel) level, itemstack, context.getPlayer(), blockhitresult.getBlockPos().relative(context.getClickedFace()), MobSpawnType.SPAWN_EGG, true, !java.util.Objects.equals(blockhitresult.getBlockPos(), context.getClickedPos()) && context.getClickedFace() == Direction.UP);

            if (spawnedEntity != null) {
                // *** THIS IS THE KEY PART ***
                // Set the owner UUID to the player who used the egg
                Player player = context.getPlayer();
                if (player != null) {
                    spawnedEntity.setOwnerUUID(player.getUUID());
                    SuperMomEntity.LOGGER.debug("Set owner {} for SuperMom spawned via egg.", player.getName().getString());
                } else {
                     SuperMomEntity.LOGGER.warn("Could not set owner for SuperMom spawned via egg: Player was null.");
                }
                // END KEY PART

                itemstack.shrink(1); // Consume the egg
                level.gameEvent(context.getPlayer(), net.minecraft.world.level.gameevent.GameEvent.ENTITY_PLACE, blockhitresult.getBlockPos());
            }

            return InteractionResult.CONSUME;
        }
    }
}