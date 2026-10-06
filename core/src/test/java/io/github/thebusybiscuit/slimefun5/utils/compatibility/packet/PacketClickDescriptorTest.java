package io.github.thebusybiscuit.slimefun5.utils.compatibility.packet;

import org.junit.jupiter.api.Test;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PacketClickDescriptorTest {
    @Test void crcSignaturesCompareByValueNotBoxedIdentity() {
        Object component = new Object();
        Map<Object, Integer> one = new IdentityHashMap<>(), two = new IdentityHashMap<>();
        one.put(component, Integer.valueOf(0x5fff1234));
        two.put(component, Integer.valueOf(0x5fff1234));
        assertNotSame(one.get(component), two.get(component));
        assertNotEquals(one, two, "Native identity-map equality is not CRC equality");
        var signature = new PacketClickDescriptor.PatchKey(one, Set.of());
        assertEquals(signature, new PacketClickDescriptor.PatchKey(two, Set.of()));
        one.clear();
        assertEquals(1, signature.added().size(), "Signature is an immutable snapshot");
    }
    @Test void exactObservedMappingOnly() {
        var map = new PacketClickDescriptor.PredictionMappings<String, String>(2);
        assertNull(map.lookup("forged")); assertTrue(map.observe("view", "canonical"));
        assertEquals("canonical", map.lookup("view")); assertNull(map.lookup("changed-pdc"));
    }
    @Test void sameMappingCanBeRepeated() {
        var map = new PacketClickDescriptor.PredictionMappings<String, String>(1);
        assertTrue(map.observe("view", "canonical")); assertTrue(map.observe("view", "canonical"));
    }
    @Test void ambiguousDisplayCannotPickAStack() {
        var map = new PacketClickDescriptor.PredictionMappings<String, String>(2);
        assertTrue(map.observe("view", "old-canonical")); assertFalse(map.observe("view", "new-canonical"));
        assertNull(map.lookup("view")); assertFalse(map.observe("view", "old-canonical"));
    }
    @Test void identityObservationInvalidatesStaleAliasWithoutGuessing() {
        var map = new PacketClickDescriptor.PredictionMappings<String, String>(2);
        assertTrue(map.observe("translated", "raw"));
        assertFalse(map.observe("translated", "translated"));
        assertNull(map.lookup("translated"), "Identity view must not be reverse-mapped into previous raw metadata");
    }
    @Test void boundNeverEvictsInFlightMappings() {
        var map = new PacketClickDescriptor.PredictionMappings<String, String>(1);
        assertTrue(map.observe("first", "a")); assertFalse(map.observe("second", "b"));
        assertEquals("a", map.lookup("first")); assertNull(map.lookup("second"));
    }
    @Test void connectionsDoNotShareMappings() {
        var one = new PacketClickDescriptor.PredictionMappings<String, String>(2);
        var two = new PacketClickDescriptor.PredictionMappings<String, String>(2);
        assertTrue(one.observe("view", "a")); assertNull(two.lookup("view"));
        assertTrue(two.observe("view", "b")); assertEquals("a", one.lookup("view"));
    }
}
