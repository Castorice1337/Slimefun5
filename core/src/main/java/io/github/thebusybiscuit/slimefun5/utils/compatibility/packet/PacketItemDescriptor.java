package io.github.thebusybiscuit.slimefun5.utils.compatibility.packet;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import javax.annotation.Nullable;

/**
 * Describes an item-bearing clientbound packet and knows how to rewrite the item(s) it carries. Resolved
 * reflectively per server version; families whose class does not resolve are simply not produced.
 */
public final class PacketItemDescriptor {

    /** Candidate simple/class names across the version range (Spigot obf + Mojang-mapped Paper). */
    private static final String[][] FAMILIES = {
        { "net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket" },
        { "net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket" },
        { "net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket" },
        { "net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket" },
        // Legacy Spigot NMS lived in net.minecraft.server.<ver>; resolved via the server package below.
        { "PacketPlayOutSetSlot" },
        { "PacketPlayOutWindowItems" },
    };

    private final Class<?> packetClass;
    private final Class<?> nmsItemClass;
    private final Constructor<?> copyConstructor;
    private final Method[] copyAccessors;

    PacketItemDescriptor(Class<?> packetClass, Class<?> nmsItemClass) {
        this.packetClass = packetClass;
        this.nmsItemClass = nmsItemClass;
        Constructor<?> constructor = null;
        Method[] accessors = null;
        try {
            if (recordComponents(packetClass) != null) {
                Object[] components = recordComponents(packetClass);
                Class<?>[] types = new Class<?>[components.length];
                accessors = new Method[components.length];
                for (int i = 0; i < components.length; i++) {
                    types[i] = (Class<?>) components[i].getClass().getMethod("getType").invoke(components[i]);
                    accessors[i] = (Method) components[i].getClass().getMethod("getAccessor").invoke(components[i]);
                    accessors[i].setAccessible(true);
                }
                constructor = packetClass.getDeclaredConstructor(types);
            } else if (packetClass.getName().equals("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket")) {
                // This modern packet is still a normal final-field class. Use its verified
                // public constructor/accessors, not reflection field order or unsafe writes.
                constructor = packetClass.getConstructor(int.class, int.class, int.class, nmsItemClass);
                accessors = new Method[] { packetClass.getMethod("getContainerId"), packetClass.getMethod("getStateId"),
                    packetClass.getMethod("getSlot"), packetClass.getMethod("getItem") };
            }
            if (constructor != null) constructor.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            constructor = null;
            accessors = null;
        }
        this.copyConstructor = constructor;
        this.copyAccessors = accessors;
    }

    public static List<PacketItemDescriptor> resolveAll() {
        Class<?> nmsItem = resolveNmsItemClass();
        List<PacketItemDescriptor> out = new ArrayList<>();
        if (nmsItem == null) {
            return out;
        }
        String legacyPkg = legacyNmsPackage();
        for (String[] family : FAMILIES) {
            for (String name : family) {
                Class<?> c = tryClass(name);
                if (c == null && legacyPkg != null && name.indexOf('.') < 0) {
                    c = tryClass(legacyPkg + '.' + name);
                }
                if (c != null) {
                    out.add(new PacketItemDescriptor(c, nmsItem));
                    break;
                }
            }
        }
        return out;
    }

    public boolean matches(Object packet) {
        return packet != null && packetClass.isInstance(packet);
    }

    /** Rewrites every NMS item the packet carries via {@code itemRewriter}. Never throws. */
    public Object rewrite(Object packet, UnaryOperator<Object> itemRewriter) {
        try {
            if (copyConstructor != null) {
                Object[] arguments = new Object[copyAccessors.length];
                boolean changed = false;
                for (int i = 0; i < arguments.length; i++) {
                    Object current = copyAccessors[i].invoke(packet);
                    arguments[i] = rewriteValue(current, itemRewriter);
                    changed |= arguments[i] != current;
                }
                // Records cannot have their final fields set on Java25. Reconstruct the
                // complete packet, including carried item and every original sync id.
                // Neither its list nor the shared canonical server items are modified.
                return changed ? copyConstructor.newInstance(arguments) : packet;
            }
            if (recordComponents(packetClass) != null) return packet; // unsupported record: safe, atomic fallback
            // Legacy mutable packet payloads. Copy lists before replacing their field:
            // list.set on an immutable list aborts the carried-item rewrite entirely.
            Field listField = PacketReflect.firstFieldOfType(packet, List.class);
            if (listField != null) {
                Object listObj = listField.get(packet);
                if (listObj instanceof List) {
                    Object rewritten = rewriteValue(listObj, itemRewriter);
                    if (rewritten != listObj) trySet(listField, packet, rewritten);
                }
            }
            // Array payload (early-legacy PacketPlayOutWindowItems carries its contents as an
            // ItemStack[] rather than a List): rewrite each element in place. General enough to cover any
            // other array-based container packet.
            Field arrayField = PacketReflect.firstFieldOfArrayType(packet, nmsItemClass);
            if (arrayField != null) {
                Object arrayObj = arrayField.get(packet);
                if (arrayObj != null && arrayObj.getClass().isArray()) {
                    int length = Array.getLength(arrayObj);
                    for (int i = 0; i < length; i++) {
                        Object el = Array.get(arrayObj, i);
                        if (nmsItemClass.isInstance(el)) {
                            Object rewritten = itemRewriter.apply(el);
                            if (rewritten != null && rewritten != el) {
                                Array.set(arrayObj, i, rewritten);
                            }
                        }
                    }
                }
            }
            // Single-item payload (SetSlot / ContainerSetSlot, or the carried/cursor item
            // alongside the list on ClientboundContainerSetContentPacket): set the field if writable.
            Field itemField = PacketReflect.firstFieldOfType(packet, nmsItemClass);
            if (itemField != null) {
                Object current = itemField.get(packet);
                if (nmsItemClass.isInstance(current)) {
                    Object rewritten = itemRewriter.apply(current);
                    if (rewritten != null && rewritten != current) {
                        trySet(itemField, packet, rewritten);
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to returning the original packet (fallback)
        }
        return packet;
    }

    /** Rewrites a detached value without mutating the source packet/list/array. */
    private Object rewriteValue(Object current, UnaryOperator<Object> itemRewriter) {
        if (nmsItemClass.isInstance(current)) {
            Object replacement = itemRewriter.apply(current);
            return replacement != null ? replacement : current;
        }
        if (current instanceof List<?>) {
            List<?> list = (List<?>) current;
            List<Object> copy = new ArrayList<>(list.size());
            boolean changed = false;
            for (Object element : list) {
                Object replacement = nmsItemClass.isInstance(element) ? rewriteValue(element, itemRewriter) : element;
                copy.add(replacement);
                changed |= replacement != element;
            }
            return changed ? copy : current;
        }
        if (current != null && current.getClass().isArray()
                && nmsItemClass.isAssignableFrom(current.getClass().getComponentType())) {
            int length = Array.getLength(current);
            Object copy = Array.newInstance(current.getClass().getComponentType(), length);
            boolean changed = false;
            for (int i = 0; i < length; i++) {
                Object element = Array.get(current, i);
                Object replacement = rewriteValue(element, itemRewriter);
                Array.set(copy, i, replacement);
                changed |= replacement != element;
            }
            return changed ? copy : current;
        }
        return current;
    }

    private static Object[] recordComponents(Class<?> type) {
        try { return (Object[]) Class.class.getMethod("getRecordComponents").invoke(type); }
        catch (ReflectiveOperationException ignored) { return null; }
    }

    private static boolean trySet(Field f, Object owner, Object value) {
        try {
            f.setAccessible(true);
            f.set(owner, value);
            return true;
        } catch (Throwable t) {
            return false; // final/immutable field on this version - leave packet unchanged
        }
    }

    @Nullable
    private static Class<?> resolveNmsItemClass() {
        Class<?> c = tryClass("net.minecraft.world.item.ItemStack");
        if (c != null) {
            return c;
        }
        String legacy = legacyNmsPackage();
        return legacy != null ? tryClass(legacy + ".ItemStack") : null;
    }

    @Nullable
    private static String legacyNmsPackage() {
        // Spigot: org.bukkit.craftbukkit.v1_8_R3 -> net.minecraft.server.v1_8_R3
        try {
            String cb = org.bukkit.Bukkit.getServer().getClass().getPackage().getName();
            int i = cb.lastIndexOf('.');
            if (i < 0) {
                return null;
            }
            String ver = cb.substring(i + 1);
            return ver.startsWith("v") ? "net.minecraft.server." + ver : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Class<?> tryClass(String n) {
        try {
            return Class.forName(n);
        } catch (Throwable t) {
            return null;
        }
    }
}
