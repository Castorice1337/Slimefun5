package io.github.thebusybiscuit.slimefun5.utils.compatibility.packet;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketItemDescriptorTest {
    record Item(String name) {}
    record Cursor(Item contents) {}
    record PlayerSlot(int slot, Item contents) {}
    record Content(int containerId, int stateId, List<Item> items, Item carried) {}

    private Object translated(Object packet) {
        return new PacketItemDescriptor(packet.getClass(), Item.class)
            .rewrite(packet, item -> new Item("中文"));
    }

    @Test void reconstructsImmutableCursor() {
        Cursor original = new Cursor(new Item("English"));
        assertEquals("中文", ((Cursor) translated(original)).contents().name());
        assertEquals("English", original.contents().name());
    }

    @Test void retainsPlayerSlotIndex() {
        PlayerSlot original = new PlayerSlot(37, new Item("English"));
        PlayerSlot result = (PlayerSlot) translated(original);
        assertEquals(37, result.slot());
        assertEquals("中文", result.contents().name());
        assertEquals("English", original.contents().name());
    }

    @Test void translatesImmutableContentAndCarriedTogether() {
        Content original = new Content(7, 42, List.of(new Item("English")), new Item("English"));
        Content result = (Content) translated(original);
        assertEquals(7, result.containerId());
        assertEquals(42, result.stateId());
        assertEquals("中文", result.items().getFirst().name());
        assertEquals("中文", result.carried().name());
        assertEquals("English", original.items().getFirst().name());
        assertEquals("English", original.carried().name());
    }

    @Test void neverMutatesSharedMutableContentList() {
        List<Item> shared = new ArrayList<>(List.of(new Item("English")));
        Content original = new Content(0, 0, shared, new Item("English"));
        Content result = (Content) translated(original);
        assertNotSame(shared, result.items());
        assertEquals("English", shared.getFirst().name());
    }

    @Test void failureReturnsOriginalWithoutPartiallyTranslatingIt() {
        Content original = new Content(7, 42, new ArrayList<>(List.of(new Item("English"))), new Item("English"));
        int[] calls = {0};
        Object result = new PacketItemDescriptor(Content.class, Item.class).rewrite(original, item -> {
            if (++calls[0] == 2) throw new IllegalStateException("fixture failure");
            return new Item("中文");
        });
        assertSame(original, result);
        assertEquals("English", original.items().getFirst().name());
        assertEquals("English", original.carried().name());
    }

    @Test void nullReplacementLeavesItemUntouched() {
        Cursor original = new Cursor(new Item("English"));
        Cursor result = (Cursor) new PacketItemDescriptor(Cursor.class, Item.class).rewrite(original, item -> null);
        assertEquals(original, result);
    }
}
