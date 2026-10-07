package com.kingdomsai.minecraft.entity;

import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.npc.Npc;
import com.kingdomsai.core.npc.Office;
import com.kingdomsai.core.npc.Profession;
import com.kingdomsai.minecraft.ServerRuntime;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * Materialização de um NPC do Core. Não é salva no chunk (shouldBeSaved = false):
 * o estado verdadeiro está no Core e a entidade é recriada quando o jogador chega perto.
 */
public class KingdomNpcEntity extends PathfinderMob {
    public static final EntityDataAccessor<Integer> SKIN = SynchedEntityData.defineId(KingdomNpcEntity.class, EntityDataSerializers.INT);

    private UUID npcId;
    private long talkingUntil;
    private UUID talkingTo;

    public KingdomNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) {
            nav.setCanOpenDoors(true);
            nav.setCanPassDoors(true);
        }
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 24.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 48.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, 0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 0.75, true) {
            @Override
            public boolean canUse() {
                return isMilitary() && super.canUse();
            }
        });
        goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        goalSelector.addGoal(3, new NpcRoutineGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override
            public boolean canUse() {
                return isMilitary() && super.canUse();
            }
        });
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, 10, true, false,
                e -> isMilitary()));
    }

    // ------------------------------------------------------------------ vínculo com o Core

    public void bind(Npc npc) {
        this.npcId = npc.id;
        entityData.set(SKIN, Math.floorMod(npc.skin, 9));
        refreshAppearance(npc);
    }

    public UUID npcId() {
        return npcId;
    }

    public Npc npc() {
        ServerRuntime rt = ServerRuntime.get();
        return rt == null || npcId == null ? null : rt.core().npc(npcId);
    }

    public int skin() {
        return entityData.get(SKIN);
    }

    public void refreshAppearance(Npc npc) {
        ChatFormatting color = npc.office != Office.NONE ? ChatFormatting.GOLD : npc.profession.isMilitary() ? ChatFormatting.RED : ChatFormatting.WHITE;
        setCustomName(Component.literal(npc.name).withStyle(color)
                .append(Component.literal(" [" + npc.title() + "]").withStyle(ChatFormatting.GRAY)));
        setCustomNameVisible(true);
        setItemSlot(EquipmentSlot.MAINHAND, toolFor(npc));
        // o corpo só veste o que o Core diz que ele tem (kit do ofício); cativo/escravizado não usa equipamento militar
        boolean free = npc.isFree();
        ItemStack head = free ? stack(npc.gear.get("head")) : ItemStack.EMPTY;
        setItemSlot(EquipmentSlot.HEAD, npc.office == Office.KING && head.isEmpty() ? new ItemStack(Items.GOLDEN_HELMET) : head);
        setItemSlot(EquipmentSlot.CHEST, free ? stack(npc.gear.get("chest")) : ItemStack.EMPTY);
        setItemSlot(EquipmentSlot.OFFHAND, free ? stack(npc.gear.get("offhand")) : ItemStack.EMPTY);
        for (EquipmentSlot s : EquipmentSlot.values()) setDropChance(s, 0f);
    }

    private static ItemStack stack(String id) {
        net.minecraft.resources.ResourceLocation rl = id != null && id.contains(":") ? net.minecraft.resources.ResourceLocation.tryParse(id) : null;
        return rl == null ? ItemStack.EMPTY : new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl));
    }

    /** A ferramenta deste tipo que ele tem de verdade na mochila; sem ela, a genérica (etapas abstratas das cadeias). */
    private static ItemStack realTool(Npc n, String type, net.minecraft.world.item.Item generic) {
        String id = com.kingdomsai.core.skill.Inventory.bestTool(n, type);
        return id != null ? stack(id) : new ItemStack(generic);
    }

    private static ItemStack toolFor(Npc n) {
        // Etapa de uma cadeia de trabalho: mostra a ferramenta ou o que está carregando.
        ItemStack duty = switch (n.heldItem == null ? "" : n.heldItem) {
            case "pickaxe" -> realTool(n, "pickaxe", Items.IRON_PICKAXE);
            case "axe" -> realTool(n, "axe", Items.IRON_AXE);
            case "hoe" -> realTool(n, "hoe", Items.IRON_HOE);
            case "book" -> new ItemStack(Items.WRITABLE_BOOK);
            case "ingot", "iron_ingot" -> new ItemStack(Items.IRON_INGOT);
            case "sword" -> new ItemStack(Items.IRON_SWORD);
            case "raw_iron" -> new ItemStack(Items.RAW_IRON);
            case "coal" -> new ItemStack(Items.COAL);
            case "wheat" -> new ItemStack(Items.WHEAT);
            case "seeds" -> new ItemStack(Items.WHEAT_SEEDS);
            case "log" -> new ItemStack(Items.OAK_LOG);
            case "stone" -> new ItemStack(Items.COBBLESTONE);
            case "letter" -> new ItemStack(Items.PAPER);
            default -> stack(n.heldItem); // ordens físicas mandam o id do item ("minecraft:stone_pickaxe")
        };
        if (!duty.isEmpty()) return duty;
        if (n.office == Office.ADVISOR || n.office == Office.CHANCELLOR) return new ItemStack(Items.WRITABLE_BOOK);
        // de folga: a ferramenta do ofício que ele tem (sem ela, mãos vazias — o armazém repõe)
        return switch (n.profession) {
            case FARMER -> stack(com.kingdomsai.core.skill.Inventory.bestTool(n, "hoe"));
            case LUMBERJACK -> stack(com.kingdomsai.core.skill.Inventory.bestTool(n, "axe"));
            case MINER -> stack(com.kingdomsai.core.skill.Inventory.bestTool(n, "pickaxe"));
            case BLACKSMITH -> new ItemStack(Items.IRON_INGOT);
            case BUILDER -> new ItemStack(Items.OAK_PLANKS);
            case GUARD, SOLDIER -> n.equipped.isEmpty() ? new ItemStack(Items.WOODEN_SWORD) : new ItemStack(Items.IRON_SWORD); // sem arma do arsenal: espada de treino
            case MERCHANT -> new ItemStack(Items.EMERALD);
            case PRIEST, SCHOLAR -> new ItemStack(Items.BOOK);
            default -> ItemStack.EMPTY;
        };
    }

    public boolean isMilitary() {
        Npc n = npc();
        return n != null && n.profession.isMilitary();
    }

    /** Último item de cadeia mostrado na mão (troca na hora em que a etapa muda). */
    private String shownHeld = "", shownEquip = "";

    public boolean isTalking() {
        return level().getGameTime() < talkingUntil;
    }

    public UUID talkingTo() {
        return talkingTo;
    }

    // ------------------------------------------------------------------ comportamento

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || tickCount % 20 != 0) return;
        Npc n = npc();
        if (n == null || !n.alive) {
            discard();
            return;
        }
        n.pos = new Pos(getBlockX(), getBlockY(), getBlockZ());
        n.materialized = true;
        String equip = n.equipped + n.gear + n.bag.keySet().stream().filter(com.kingdomsai.core.skill.Inventory::isTool).toList();
        if (tickCount % 100 == 0 || !n.heldItem.equals(shownHeld) || !equip.equals(shownEquip)) {
            shownHeld = n.heldItem;
            shownEquip = equip;
            refreshAppearance(n);
        }
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!level().isClientSide && player instanceof ServerPlayer sp) {
            talkingUntil = level().getGameTime() + 20 * 20;
            talkingTo = sp.getUUID();
            getNavigation().stop();
            ServerRuntime rt = ServerRuntime.get();
            if (rt != null) rt.onNpcInteract(sp, this);
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) {
            ServerRuntime rt = ServerRuntime.get();
            if (rt != null) rt.onNpcDied(this, source);
        }
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceSq) {
        return false;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }
}
