package io.github.thebusybiscuit.slimefun5.utils.compatibility.packet;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import javax.annotation.Nullable;
import org.bukkit.entity.Player;

/**
 * Inverse display mapping for modern container predictions. Only hashes of exact items actually
 * rendered by this viewer's connection are translated back. Counts, click actions and sync ids
 * remain client claims and are validated by the native handler; inventories are never read/written.
 */
public final class PacketClickDescriptor {
    private final Class<?> packetClass;
    private final Class<?> actualHashClass;
    private final Method createHash;
    private final Method item;
    private final Method count;
    private final Method components;
    private final Method addedComponents;
    private final Method removedComponents;
    private final Constructor<?> actualHashConstructor;
    private final Constructor<?> packetConstructor;
    private final Method[] accessors;
    private final Constructor<?> mapConstructor;

    private PacketClickDescriptor() throws ReflectiveOperationException {
        packetClass = Class.forName("net.minecraft.network.protocol.game.ServerboundContainerClickPacket");
        Class<?> hashClass = Class.forName("net.minecraft.network.HashedStack");
        Class<?> hasher = Class.forName("net.minecraft.network.HashedPatchMap$HashGenerator");
        Class<?> nmsItem = Class.forName("net.minecraft.world.item.ItemStack");
        actualHashClass = Class.forName("net.minecraft.network.HashedStack$ActualItem");
        createHash = hashClass.getMethod("create", nmsItem, hasher);
        item = actualHashClass.getMethod("item");
        count = actualHashClass.getMethod("count");
        components = actualHashClass.getMethod("components");
        addedComponents = components.getReturnType().getMethod("addedComponents");
        removedComponents = components.getReturnType().getMethod("removedComponents");
        actualHashConstructor = actualHashClass.getConstructor(item.getReturnType(), int.class, components.getReturnType());
        Object[] records = (Object[]) Class.class.getMethod("getRecordComponents").invoke(packetClass);
        String[] expected = {"containerId", "stateId", "slotNum", "buttonNum", "containerInput", "changedSlots", "carriedItem"};
        if (records == null || records.length != expected.length) throw new ReflectiveOperationException("Unknown click schema");
        Class<?>[] types = new Class<?>[records.length];
        accessors = new Method[records.length];
        for (int i = 0; i < records.length; i++) {
            if (!expected[i].equals((String) records[i].getClass().getMethod("getName").invoke(records[i]))) throw new ReflectiveOperationException("Unknown click field");
            types[i] = (Class<?>) records[i].getClass().getMethod("getType").invoke(records[i]); accessors[i] = (Method) records[i].getClass().getMethod("getAccessor").invoke(records[i]);
        }
        packetConstructor = packetClass.getConstructor(types);
        mapConstructor = Class.forName("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap").getConstructor();
    }

    /** Null on legacy or unsupported schemas. A modern resolution failure must disable item rendering. */
    @Nullable public static PacketClickDescriptor resolve() {
        try { return new PacketClickDescriptor(); }
        catch (ReflectiveOperationException | LinkageError error) { return null; }
    }

    public static boolean usesHashedPredictions() {
        try { Class.forName("net.minecraft.network.HashedStack"); return true; }
        catch (ClassNotFoundException legacy) { return false; }
        catch (LinkageError unsupported) { return true; }
    }

    /** Main-thread snapshot of the actual player's vanilla, registry-aware CRC32C hasher. */
    public Session sessionFor(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Object synchronizer = handle.getClass().getField("containerSynchronizer").get(handle);
        Method create = Class.forName("net.minecraft.world.inventory.ContainerSynchronizer").getMethod("createSlot");
        Object slot = create.invoke(synchronizer);
        Field hasher = slot.getClass().getDeclaredField("hasher"); hasher.setAccessible(true);
        return new Session(hasher.get(slot));
    }

    /** Also used by isolated native fixtures with the very same vanilla hasher. */
    public Session newSession(Object hasher) { return new Session(hasher); }

    static final class PatchKey {
        private final Map<Object, Integer> added;
        private final Set<Object> removed;
        PatchKey(Map<Object, Integer> added, Set<Object> removed) {
            this.added = Collections.unmodifiableMap(new HashMap<>(added));
            this.removed = Collections.unmodifiableSet(new HashSet<>(removed));
        }
        Map<Object, Integer> added() { return added; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof PatchKey)) return false;
            PatchKey key = (PatchKey) other;
            return added.equals(key.added) && removed.equals(key.removed);
        }
        @Override public int hashCode() { return Objects.hash(added, removed); }
    }
    private static final class Key {
        private final Object item;
        private final PatchKey renderedComponents;
        Key(Object item, PatchKey renderedComponents) {
            this.item = item; this.renderedComponents = renderedComponents;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return item.equals(key.item) && renderedComponents.equals(key.renderedComponents);
        }
        @Override public int hashCode() { return Objects.hash(item, renderedComponents); }
    }

    @SuppressWarnings("unchecked") private PatchKey signature(Object patch) throws ReflectiveOperationException {
        // Vanilla uses identity maps for component types AND boxed integer values. Their equals()
        // is not semantic CRC equality: normalize only the immutable signature, never the NMS patch.
        return new PatchKey((Map<Object, Integer>) addedComponents.invoke(patch),
            (Set<Object>) removedComponents.invoke(patch));
    }

    public final class Session {
        private final Object hasher;
        private final PredictionMappings<Key, PatchKey> mappings = new PredictionMappings<>(4096);
        private final Map<PatchKey, Object> originals = new HashMap<>();
        private Session(Object hasher) { this.hasher = hasher; }

        /** False means unsafe/unknown mapping: do not send the translated item at all. */
        public boolean observe(Object canonical, Object rendered) {
            try {
                Object original = createHash.invoke(null, canonical, hasher);
                Object display = createHash.invoke(null, rendered, hasher);
                if (!actualHashClass.isInstance(original) || !actualHashClass.isInstance(display)) return original.equals(display);
                if (!item.invoke(original).equals(item.invoke(display)) || !count.invoke(original).equals(count.invoke(display))) return false;
                Object originalPatch = components.invoke(original);
                Object renderedPatch = components.invoke(display);
                PatchKey originalKey = signature(originalPatch), renderedKey = signature(renderedPatch);
                if (originalKey.equals(renderedKey)) {
                    // Identity views must invalidate a stale translated alias, too. The item is
                    // already unchanged, so it remains safe to send when the ledger rejects it.
                    boolean accepted = mappings.observe(new Key(item.invoke(display), renderedKey), originalKey);
                    if (accepted) originals.putIfAbsent(originalKey, originalPatch);
                    return true;
                }
                boolean accepted = mappings.observe(new Key(item.invoke(display), renderedKey), originalKey);
                if (accepted) originals.putIfAbsent(originalKey, originalPatch);
                return accepted;
            } catch (ReflectiveOperationException | RuntimeException error) { return false; }
        }

        private Object inverse(Object hash) throws ReflectiveOperationException {
            if (!actualHashClass.isInstance(hash)) return hash; // EMPTY stays EMPTY, never becomes a stack
            PatchKey originalKey = mappings.lookup(new Key(item.invoke(hash), signature(components.invoke(hash))));
            if (originalKey == null) return hash; // forged/changed metadata/unknown hashes stay untouched
            Object originalPatch = originals.get(originalKey);
            return actualHashConstructor.newInstance(item.invoke(hash), count.invoke(hash), originalPatch);
        }

        /** Atomic packet copy. Original map, every id/action/slot/button and submitted count preserved. */
        @SuppressWarnings("unchecked") public Object rewrite(Object packet) {
            if (!packetClass.isInstance(packet)) return packet;
            try {
                Object[] args = new Object[accessors.length];
                for (int i = 0; i < args.length; i++) args[i] = accessors[i].invoke(packet);
                Map<Object, Object> slots = (Map<Object, Object>) args[5];
                Map<Object, Object> copy = (Map<Object, Object>) mapConstructor.newInstance();
                boolean changed = false;
                for (Map.Entry<Object, Object> entry : slots.entrySet()) {
                    Object replacement = inverse(entry.getValue());
                    copy.put(entry.getKey(), replacement); changed |= replacement != entry.getValue();
                }
                Object carried = inverse(args[6]); changed |= carried != args[6];
                if (!changed) return packet;
                args[5] = copy; args[6] = carried;
                return packetConstructor.newInstance(args);
            } catch (ReflectiveOperationException | RuntimeException error) { return packet; }
        }
    }

    /** Bounded, connection-local and non-evicting: ambiguity never guesses which physical stack wins. */
    static final class PredictionMappings<K, V> {
        private final Map<K, V> values = new HashMap<>();
        private final int limit;
        PredictionMappings(int limit) { this.limit = limit; }
        boolean observe(K key, V value) {
            if (values.containsKey(key)) {
                V previous = values.get(key);
                if (previous != null && previous.equals(value)) return true;
                values.put(key, null); return false; // permanent ambiguity until this connection closes
            }
            if (values.size() >= limit) return false; // do not evict in-flight mappings
            values.put(key, value); return true;
        }
        V lookup(K key) { return values.get(key); }
    }
}
