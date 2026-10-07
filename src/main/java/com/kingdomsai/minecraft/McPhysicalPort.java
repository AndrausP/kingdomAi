package com.kingdomsai.minecraft;

import com.kingdomsai.core.common.Pos;
import com.kingdomsai.core.port.PhysicalPort;
import com.kingdomsai.minecraft.entity.KingdomNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * As mãos dos NPCs no Minecraft. Só traduz: o Core já decidiu o que quebrar/pegar/fabricar e validou.
 * Usa as regras do próprio jogo — tabela de drops (pedra sem picareta não dá nada), receitas carregadas
 * (inclusive de outros mods), baús duplos, sons e a animação da tampa do baú e da rachadura do bloco.
 */
public final class McPhysicalPort implements PhysicalPort {
    private final ServerLevel level;
    private final NpcMaterializer materializer;
    private final MinecraftServer server;
    private final Map<String, List<PhysicalPort.Recipe>> recipeCache = new HashMap<>();

    public McPhysicalPort(ServerLevel level, NpcMaterializer materializer, MinecraftServer server) {
        this.level = level;
        this.materializer = materializer;
        this.server = server;
    }

    private static BlockPos bp(Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    /** A ferramenta de verdade da mochila ("minecraft:stone_pickaxe") ou, para prever drops, só o tipo ("pickaxe"). */
    private static ItemStack toolStack(String tool) {
        if (tool == null || tool.isEmpty()) return ItemStack.EMPTY;
        if (tool.contains(":")) {
            Item it = item(tool);
            return it == Items.AIR ? ItemStack.EMPTY : new ItemStack(it);
        }
        return switch (tool) {
            case "pickaxe" -> new ItemStack(Items.IRON_PICKAXE);
            case "axe" -> new ItemStack(Items.IRON_AXE);
            case "shovel" -> new ItemStack(Items.IRON_SHOVEL);
            case "hoe" -> new ItemStack(Items.IRON_HOE);
            default -> ItemStack.EMPTY;
        };
    }

    // ------------------------------------------------------------------ blocos

    @Override
    public boolean isLoaded(Pos p) {
        return level.isLoaded(bp(p));
    }

    @Override
    public BlockInfo block(Pos p) {
        BlockPos pos = bp(p);
        if (!level.isLoaded(pos)) return BlockInfo.UNKNOWN;
        BlockState s = level.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        boolean air = s.isAir();
        boolean fluid = s.getBlock() instanceof LiquidBlock;
        float hard = s.getDestroySpeed(level, pos);
        boolean nearFluid = !fluid && !s.getFluidState().isEmpty(); // bloco "alagado"
        for (Direction d : Direction.values())
            if (d != Direction.DOWN && !level.getFluidState(pos.relative(d)).isEmpty()) nearFluid = true;
        String tool = s.is(BlockTags.MINEABLE_WITH_PICKAXE) ? "pickaxe" : s.is(BlockTags.MINEABLE_WITH_AXE) ? "axe"
                : s.is(BlockTags.MINEABLE_WITH_SHOVEL) ? "shovel" : s.is(BlockTags.MINEABLE_WITH_HOE) ? "hoe" : null;
        boolean leaves = s.is(BlockTags.LEAVES);
        String drop = null;
        if (!air && !fluid && !leaves) {
            for (ItemStack st : Block.getDrops(s, level, pos, level.getBlockEntity(pos), null, toolStack(tool)))
                if (!st.isEmpty()) {
                    drop = id(st.getItem());
                    break;
                }
        }
        boolean breakable = !air && !fluid && hard >= 0 && !s.is(Blocks.BEDROCK) && !s.is(BlockTags.WITHER_IMMUNE);
        return new BlockInfo(id, air, breakable, hard, s.hasBlockEntity(), nearFluid, fluid, s.is(BlockTags.LOGS), leaves,
                tool, s.requiresCorrectToolForDrops(), drop);
    }

    @Override
    public Map<String, Integer> breakBlock(UUID npc, Pos p, String tool) {
        BlockPos pos = bp(p);
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) return Map.of();
        KingdomNpcEntity e = materializer.entity(npc);
        ItemStack stack = toolStack(tool);
        BlockEntity be = level.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(s, level, pos, be, e, stack);
        if (s.requiresCorrectToolForDrops() && (stack.isEmpty() || !stack.isCorrectToolForDrops(s))) drops = List.of();
        level.destroyBlock(pos, false, e); // partículas + som de quebra
        if (e != null) level.destroyBlockProgress(e.getId(), pos, -1);
        if (!drops.isEmpty()) s.spawnAfterBreak(level, pos, stack, true); // experiência dos minérios
        Map<String, Integer> out = new TreeMap<>();
        for (ItemStack st : drops) if (!st.isEmpty()) out.merge(id(st.getItem()), st.getCount(), Integer::sum);
        return out;
    }

    @Override
    public int growth(Pos p) {
        BlockPos pos = bp(p);
        if (!level.isLoaded(pos)) return -1;
        BlockState s = level.getBlockState(pos);
        if (!(s.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop)) return -1;
        if (crop.isMaxAge(s)) return 100;
        // idade pela propriedade "age" (trigo 0-7, beterraba 0-3...), sem depender de getters protegidos
        for (var prop : s.getProperties())
            if (prop instanceof net.minecraft.world.level.block.state.properties.IntegerProperty ip && ip.getName().equals("age")) {
                int max = java.util.Collections.max(ip.getPossibleValues());
                return max <= 0 ? 0 : Math.min(99, 100 * s.getValue(ip) / max);
            }
        return 0;
    }

    @Override
    public boolean till(UUID npc, Pos p) {
        BlockPos pos = bp(p);
        if (!level.isLoaded(pos)) return false;
        BlockState s = level.getBlockState(pos);
        if (!(s.is(Blocks.DIRT) || s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT_PATH))) return false;
        if (!level.getBlockState(pos.above()).isAir()) return false;
        level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1f, 1f);
        KingdomNpcEntity e = materializer.entity(npc);
        if (e != null) e.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    @Override
    public boolean place(UUID npc, Pos p, String blockId) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return false;
        Block b = BuiltInRegistries.BLOCK.get(rl);
        if (b == Blocks.AIR) return false;
        BlockPos pos = bp(p);
        BlockState st = b.defaultBlockState();
        if (!level.getBlockState(pos).canBeReplaced() || !st.canSurvive(level, pos)) return false;
        level.setBlock(pos, st, Block.UPDATE_ALL);
        level.playSound(null, pos, st.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1f, 1f);
        return true;
    }

    // ------------------------------------------------------------------ baús

    /** Baús de outros mods (Sophisticated Backpacks, armazéns...) que só expõem a capability de itens do NeoForge. */
    private net.neoforged.neoforge.items.IItemHandler handlerAt(BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        return level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, pos, null);
    }

    private Container containerAt(BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        BlockState s = level.getBlockState(pos);
        if (s.getBlock() instanceof ChestBlock cb) {
            Container c = ChestBlock.getContainer(cb, s, level, pos, true); // baú duplo inteiro
            if (c != null) return c;
        }
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }

    @Override
    public Map<String, Integer> container(Pos p) {
        Container c = containerAt(bp(p));
        if (c == null) {
            var h = handlerAt(bp(p));
            if (h == null) return null;
            Map<String, Integer> m = new TreeMap<>();
            for (int i = 0; i < h.getSlots(); i++) {
                ItemStack st = h.getStackInSlot(i);
                if (!st.isEmpty()) m.merge(id(st.getItem()), st.getCount(), Integer::sum);
            }
            return m;
        }
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack st = c.getItem(i);
            if (!st.isEmpty()) m.merge(id(st.getItem()), st.getCount(), Integer::sum);
        }
        return m;
    }

    @Override
    public int take(UUID npc, Pos chest, String itemId, int n) {
        Container c = containerAt(bp(chest));
        Item it = item(itemId);
        if (it == Items.AIR) return 0;
        if (c == null) {
            var h = handlerAt(bp(chest));
            if (h == null) return 0;
            int left = n;
            for (int i = 0; i < h.getSlots() && left > 0; i++) {
                if (!h.getStackInSlot(i).is(it)) continue;
                left -= h.extractItem(i, left, false).getCount();
            }
            return n - left;
        }
        int left = n;
        for (int i = 0; i < c.getContainerSize() && left > 0; i++) {
            ItemStack st = c.getItem(i);
            if (st.isEmpty() || !st.is(it)) continue;
            int t = Math.min(left, st.getCount());
            c.removeItem(i, t);
            left -= t;
        }
        c.setChanged();
        return n - left;
    }

    @Override
    public Map<String, Integer> put(UUID npc, Pos chest, Map<String, Integer> items) {
        Container c = containerAt(bp(chest));
        if (c == null) {
            var h = handlerAt(bp(chest));
            if (h == null) return items;
            Map<String, Integer> rest = new TreeMap<>();
            for (var e : items.entrySet()) {
                Item it = item(e.getKey());
                if (it == Items.AIR) continue;
                int left = e.getValue();
                int max = new ItemStack(it).getMaxStackSize();
                while (left > 0) {
                    int n = Math.min(left, max);
                    ItemStack r = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(h, new ItemStack(it, n), false);
                    left -= n - r.getCount();
                    if (!r.isEmpty()) break;
                }
                if (left > 0) rest.put(e.getKey(), left);
            }
            return rest;
        }
        Map<String, Integer> rest = new TreeMap<>();
        for (var e : items.entrySet()) {
            Item it = item(e.getKey());
            if (it == Items.AIR) continue;
            int left = e.getValue();
            int max = new ItemStack(it).getMaxStackSize();
            while (left > 0) {
                int n = Math.min(left, max);
                ItemStack r = HopperBlockEntity.addItem(null, c, new ItemStack(it, n), null);
                left -= n - r.getCount();
                if (!r.isEmpty()) break; // baú cheio
            }
            if (left > 0) rest.put(e.getKey(), left);
        }
        c.setChanged();
        return rest;
    }

    // ------------------------------------------------------------------ receitas

    @Override
    public List<PhysicalPort.Recipe> recipes(String itemId) {
        return recipeCache.computeIfAbsent(itemId, this::loadRecipes);
    }

    private List<PhysicalPort.Recipe> loadRecipes(String itemId) {
        Item target = item(itemId);
        if (target == Items.AIR) return List.of();
        List<PhysicalPort.Recipe> out = new ArrayList<>();
        RecipeManager rm = level.getRecipeManager();
        for (RecipeHolder<CraftingRecipe> h : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe r = h.value();
            ItemStack res = r.getResultItem(level.registryAccess());
            if (res.isEmpty() || !res.is(target)) continue;
            List<List<String>> ings = ingredients(r.getIngredients());
            if (ings.isEmpty()) continue; // receitas especiais (fogos, mapas...) não entram
            boolean table = r instanceof ShapedRecipe sr ? sr.getWidth() > 2 || sr.getHeight() > 2 : ings.size() > 4;
            out.add(new PhysicalPort.Recipe(itemId, res.getCount(), ings, table ? PhysicalPort.Station.CRAFTING_TABLE : PhysicalPort.Station.NONE));
        }
        for (RecipeHolder<SmeltingRecipe> h : rm.getAllRecipesFor(RecipeType.SMELTING)) {
            SmeltingRecipe r = h.value();
            ItemStack res = r.getResultItem(level.registryAccess());
            if (res.isEmpty() || !res.is(target)) continue;
            List<List<String>> ings = ingredients(r.getIngredients());
            if (!ings.isEmpty()) out.add(new PhysicalPort.Recipe(itemId, res.getCount(), ings, PhysicalPort.Station.FURNACE));
        }
        return List.copyOf(out);
    }

    private static List<List<String>> ingredients(NonNullList<Ingredient> list) {
        List<List<String>> out = new ArrayList<>();
        for (Ingredient ing : list) {
            if (ing.isEmpty()) continue;
            List<String> opts = new ArrayList<>();
            for (ItemStack st : ing.getItems()) {
                String id = id(st.getItem());
                if (!opts.contains(id)) opts.add(id);
                if (opts.size() >= 16) break;
            }
            if (!opts.isEmpty()) out.add(opts);
        }
        return out;
    }

    @Override
    public boolean itemExists(String itemId) {
        ResourceLocation rl = ResourceLocation.tryParse(itemId);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
    }

    @Override
    public Pos findNear(Pos p, String blockId, int radius) {
        ResourceLocation rl = ResourceLocation.tryParse(blockId);
        if (rl == null) return null;
        Block target = BuiltInRegistries.BLOCK.get(rl);
        int r = Math.min(32, radius);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Pos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                m.set(p.x() + dx, p.y(), p.z() + dz);
                if (!level.isLoaded(m)) continue;
                for (int dy = -4; dy <= 4; dy++) {
                    m.set(p.x() + dx, p.y() + dy, p.z() + dz);
                    if (!level.getBlockState(m).is(target)) continue;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < bd) {
                        bd = d;
                        best = new Pos(m.getX(), m.getY(), m.getZ());
                    }
                }
            }
        return best;
    }

    // ------------------------------------------------------------------ rei

    @Override
    public boolean give(UUID npc, UUID player, Map<String, Integer> items) {
        ServerPlayer sp = server.getPlayerList().getPlayer(player);
        if (sp == null) return false;
        for (var e : items.entrySet()) {
            Item it = item(e.getKey());
            if (it == Items.AIR) continue;
            int left = e.getValue();
            int max = new ItemStack(it).getMaxStackSize();
            while (left > 0) {
                int n = Math.min(left, max);
                ItemStack st = new ItemStack(it, n);
                if (!sp.getInventory().add(st)) sp.drop(st, false); // inventário cheio: cai aos pés do rei
                left -= n;
            }
        }
        level.playSound(null, sp.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.4f, 1.2f);
        return true;
    }

    /**
     * O súdito age em nome do rei: pergunta ao jogo se o rei poderia quebrar ali. Spawn protegido do servidor e
     * borda do mundo (mayInteract) e claims de outros mods (BlockEvent.BreakEvent, cancelado por eles).
     * Rei offline: usa o jogador "falso" padrão do NeoForge, que mods de proteção tratam como estranho.
     */
    @Override
    public boolean mayBreak(UUID player, Pos p) {
        BlockPos pos = bp(p);
        if (!level.isLoaded(pos)) return false;
        net.minecraft.world.entity.player.Player who = player == null ? null : server.getPlayerList().getPlayer(player);
        if (who == null) who = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        if (!level.mayInteract(who, pos)) return false;
        var ev = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), who);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(ev);
        return !ev.isCanceled();
    }

    @Override
    public void drop(Pos p, Map<String, Integer> items) {
        for (var e : items.entrySet()) {
            Item it = item(e.getKey());
            if (it == Items.AIR) continue;
            int left = e.getValue();
            int max = new ItemStack(it).getMaxStackSize();
            while (left > 0) {
                int n = Math.min(left, max);
                net.minecraft.world.Containers.dropItemStack(level, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5, new ItemStack(it, n));
                left -= n;
            }
        }
    }

    @Override
    public boolean chunkForced(int chunkX, int chunkZ) {
        return level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunkX, chunkZ));
    }

    @Override
    public void forceChunk(int chunkX, int chunkZ, boolean on) {
        // mesmo mecanismo do /forceload: o chunk continua carregado e "ticando" sem jogador por perto
        level.setChunkForced(chunkX, chunkZ, on);
    }

    @Override
    public void animate(UUID npc, Pos at, Anim anim, int stage) {
        KingdomNpcEntity e = materializer.entity(npc);
        BlockPos pos = at == null ? null : bp(at);
        switch (anim) {
            case SWING -> {
                if (e == null) return;
                e.swing(InteractionHand.MAIN_HAND);
                if (pos != null) e.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            }
            case CRACK -> {
                if (e != null && pos != null) level.destroyBlockProgress(e.getId(), pos, stage);
            }
            case CHEST_OPEN, CHEST_CLOSE -> {
                if (pos == null) return;
                BlockState s = level.getBlockState(pos);
                boolean open = anim == Anim.CHEST_OPEN;
                if (s.getBlock() instanceof ChestBlock) level.blockEvent(pos, s.getBlock(), 1, open ? 1 : 0); // tampa
                level.playSound(null, pos, s.is(Blocks.BARREL) ? (open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE)
                        : (open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE), SoundSource.BLOCKS, 0.5f, 1f);
            }
            case CRAFT -> {
                if (pos != null) level.playSound(null, pos, SoundEvents.VILLAGER_WORK_TOOLSMITH, SoundSource.NEUTRAL, 0.7f, 1f);
                if (e != null) e.swing(InteractionHand.MAIN_HAND);
            }
            case PLANT -> {
                if (pos != null) level.playSound(null, pos, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 1f, 1f);
            }
            case GIVE -> {
                if (e != null) e.swing(InteractionHand.MAIN_HAND);
            }
        }
    }
}
