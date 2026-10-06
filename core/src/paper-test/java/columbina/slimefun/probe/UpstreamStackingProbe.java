package columbina.slimefun.probe;

import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.Connection;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.HashOps;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import io.github.thebusybiscuit.slimefun5.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun5.implementation.Slimefun;

/** Actual byte-codec boundary, not a decoded-object-only prediction fixture. Local lab only. */
public final class UpstreamStackingProbe extends JavaPlugin {
    private int checks;
    private final List<String> failures=new ArrayList<>(), observations=new ArrayList<>();
    private MinecraftServer server;
    private HashedPatchMap.HashGenerator hasher;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try { runChecks(); } catch(Throwable error) {error.printStackTrace();failures.add(error.toString());}
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.writeString(getDataFolder().toPath().resolve("result.json"),
                    "{\"pass\":"+failures.isEmpty()+",\"checks\":"+checks+",\"failures\":"+json(failures)+",\"observations\":"+json(observations)+"}");
                getLogger().info("ITEM_TRANSLATION_NATIVE_"+(failures.isEmpty()?"PASS":"FAIL")+" checks="+checks+" failures="+failures.size());
            } catch(Exception error) {throw new RuntimeException(error);}
        },60L);
    }

    private void runChecks() throws Exception {
        check(Bukkit.getOnlinePlayers().isEmpty(),"isolated no players");
        server=((CraftServer)Bukkit.getServer()).getServer();
        var ops=server.registryAccess().createSerializationContext(HashOps.CRC32C_INSTANCE);
        hasher=c->c.encodeValue(ops).getOrThrow().asInt();
        observations.add("Slimefun version="+Slimefun.instance().getDescription().getVersion());
        stack("vanilla",new ItemStack(net.minecraft.world.item.Items.STONE,5),new ItemStack(net.minecraft.world.item.Items.STONE,9),true);
        altarRoundTrips();
        for(String id:List.of("ALUMINUM_DUST","IRON_DUST","MAGIC_LUMP_1")) {
            for(String prefix:List.of("english-","chinese-")) {
                var source=CraftItemStack.asNMSCopy(SlimefunItem.getById(id).getItem().clone()).copyWithCount(5);
                stack(prefix+id,source,source.copyWithCount(9),true);
            }
        }
    }

    private void altarRoundTrips() throws Exception {
        var pedestal=(io.github.thebusybiscuit.slimefun5.implementation.items.altar.AncientPedestal) SlimefunItem.getById("ANCIENT_PEDESTAL");
        var altar=(io.github.thebusybiscuit.slimefun5.implementation.items.altar.AncientAltar) SlimefunItem.getById("ANCIENT_ALTAR");
        var handler=new io.github.thebusybiscuit.slimefun5.implementation.listeners.AncientAltarListener(Slimefun.instance(),altar,pedestal);
        var use=handler.getClass().getDeclaredMethod("usePedestal",org.bukkit.block.Block.class,org.bukkit.entity.Player.class); use.setAccessible(true);
        var profile=new GameProfile(UUID.randomUUID(),"AltarRoundTrip");
        var player=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());player.valid=true;
        var bukkit=player.getBukkitEntity(); bukkit.setGameMode(org.bukkit.GameMode.SURVIVAL);
        var block=Bukkit.getWorlds().get(0).getBlockAt(8,5,8); block.setType(org.bukkit.Material.DISPENSER);block.getRelative(0,1,0).setType(org.bukkit.Material.AIR);
        for(String id:List.of("ALUMINUM_DUST","IRON_DUST","MAGIC_LUMP_1")) {
            var source=SlimefunItem.getById(id).getItem().clone();source.setAmount(32);
            bukkit.getInventory().clear();bukkit.getInventory().setItemInMainHand(source.clone());
            int completed=0;
            for(int i=0;i<64;i++) {
                use.invoke(handler,block,bukkit); // actual place route, consumes one
                var entity=pedestal.getPlacedItem(block);
                check(entity.isPresent(),id+" rapid pedestal item exists round="+i);
                if(entity.isEmpty())break;
                var restored=pedestal.getOriginalItemStack(entity.get());
                check(restored.isSimilar(source),id+" original metadata roundtrip="+i);
                use.invoke(handler,block,bukkit); // actual remove/addItem route
                check(bukkit.getInventory().getItemInMainHand().getAmount()==32,id+" rapid return amount="+i);
                check(bukkit.getInventory().getItemInMainHand().isSimilar(source),id+" rapid return physical metadata="+i);
                completed++;
            }
            observations.add("official altar rapid place/take "+id+" rounds="+completed+" total="+bukkit.getInventory().getItemInMainHand().getAmount()+" unchangedCanonical="+bukkit.getInventory().getItemInMainHand().isSimilar(source));
        }
        var stand=pedestal.getArmorStand(block,false);if(stand!=null)stand.remove();block.setType(org.bukkit.Material.AIR);
    }

    private Object wire(Object packet) {
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),server.registryAccess());
        try {
            if(packet instanceof ClientboundContainerSetContentPacket p) {ClientboundContainerSetContentPacket.STREAM_CODEC.encode(buf,p);return ClientboundContainerSetContentPacket.STREAM_CODEC.decode(buf);}
            if(packet instanceof ClientboundContainerSetSlotPacket p) {ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buf,p);return ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buf);}
            if(packet instanceof ClientboundSetCursorItemPacket p) {ClientboundSetCursorItemPacket.STREAM_CODEC.encode(buf,p);return ClientboundSetCursorItemPacket.STREAM_CODEC.decode(buf);}
            if(packet instanceof ClientboundSetPlayerInventoryPacket p) {ClientboundSetPlayerInventoryPacket.STREAM_CODEC.encode(buf,p);return ClientboundSetPlayerInventoryPacket.STREAM_CODEC.decode(buf);}
            if(packet instanceof ServerboundContainerClickPacket p) {ServerboundContainerClickPacket.STREAM_CODEC.encode(buf,p);return ServerboundContainerClickPacket.STREAM_CODEC.decode(buf);}
            return packet;
        } finally {buf.release();}
    }

    private void stack(String label,ItemStack source) throws Exception {
        stack(label,source,source.copyWithCount(7),true);
    }

    @SuppressWarnings("unchecked") private void stack(String label,ItemStack source,ItemStack target,boolean physicallyCompatible) throws Exception {
        ItemStack original=source.copy();
        ItemStack originalTarget=target.copy();
        GameProfile profile=new GameProfile(UUID.randomUUID(),"WireStackLab");
        ServerPlayer player=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
        player.setHealth(20);player.valid=true;
        Connection connection=new Connection(PacketFlow.SERVERBOUND);EmbeddedChannel channel=new EmbeddedChannel();connection.channel=channel;
        var listener=new ServerGamePacketListenerImpl(server,connection,player,CommonListenerCookie.createInitial(profile,false));
        channel.pipeline().addLast("wire-boundary",new ChannelOutboundHandlerAdapter(){
            @Override public void write(ChannelHandlerContext ctx,Object msg,ChannelPromise promise) throws Exception {super.write(ctx,wire(msg),promise);}
        });
        channel.pipeline().addLast("packet_handler",new ChannelInboundHandlerAdapter(){
            @Override public void channelRead(ChannelHandlerContext ctx,Object msg) {listener.handleContainerClick((ServerboundContainerClickPacket)msg);}
        });
        Object service=Slimefun.getPacketTranslationService();
        Method inject=service.getClass().getDeclaredMethod("inject",org.bukkit.entity.Player.class);inject.setAccessible(true);if(Slimefun.getCfg().getBoolean("translation.packets")) inject.invoke(service,player.getBukkitEntity());
        Field languages=service.getClass().getDeclaredField("languageCache");languages.setAccessible(true);
        ((Map<UUID,String>)languages.get(service)).put(player.getUUID(),label.startsWith("english-") ? "en" : "zh-CN");
        try {
            if(label.equals("synthetic-identity-alias-history")) {
                var canonical=CraftItemStack.asNMSCopy(SlimefunItem.getById("IRON_DUST").getItem());
                channel.writeOutbound(new ClientboundContainerSetSlotPacket(7,0,20,canonical));drain(channel);
            }
            var inventory=new SimpleContainer(27);inventory.setItem(0,source.copy());inventory.setItem(1,target.copy());
            var menu=ChestMenu.threeRows(7,player.getInventory(),inventory);menu.checkReachable=false;player.containerMenu=menu;player.initMenu(menu);
            List<Object> initial=drain(channel);ClientboundContainerSetContentPacket content=null;
            for(Object packet:initial)if(packet instanceof ClientboundContainerSetContentPacket p)content=p;
            check(content!=null,label+" actual initial content reached codec");
            ItemStack a=content.items().get(0),b=content.items().get(1);
            boolean clientMerges=ItemStack.isSameItemSameComponents(a,b);
            check(ItemStack.isSameItemSameComponents(source,target)==physicallyCompatible,label+" physical compatibility matches fixture expectation");
            check(a.getCount()==source.getCount()&&b.getCount()==target.getCount(),label+" actual wire counts preserved");
            var first=new Int2ObjectOpenHashMap<HashedStack>();first.put(0,HashedStack.EMPTY);
            int state=content.stateId();
            channel.writeInbound(wire(new ServerboundContainerClickPacket(7,state,(short)0,(byte)0,ContainerInput.PICKUP,first,HashedStack.create(a,hasher))));
            int total=source.getCount()+target.getCount();
            var second=new Int2ObjectOpenHashMap<HashedStack>();
            second.put(1,HashedStack.create(clientMerges?b.copyWithCount(total):a,hasher));
            // Real quick client still has the initial state id: corrections have not arrived yet.
            channel.writeInbound(wire(new ServerboundContainerClickPacket(7,state,(short)1,(byte)0,ContainerInput.PICKUP,second,clientMerges?HashedStack.EMPTY:HashedStack.create(b,hasher))));
            List<Object> corrections=drain(channel);ItemStack cursor=ItemStack.EMPTY;int cursorCorrections=0,fullCorrections=0;
            for(Object packet:corrections) {
                if(packet instanceof ClientboundSetCursorItemPacket p) {cursor=p.contents();cursorCorrections++;}
                if(packet instanceof ClientboundContainerSetContentPacket p) {cursor=p.carriedItem();fullCorrections++;}
            }
            observations.add(label+" wireMerge: source="+source.getCount()+", target="+target.getCount()+", clientMerges="+clientMerges+", physicallyCompatible="+physicallyCompatible+", slot="+menu.getSlot(1).getItem().getCount()+", serverCursor="+menu.getCarried().getCount()+", delayedCursor="+cursor.getCount()+", cursorCorrections="+cursorCorrections+", fullCorrections="+fullCorrections
                +", canonicalName="+source.get(DataComponents.CUSTOM_NAME)+", decodedName="+a.get(DataComponents.CUSTOM_NAME));
            check(menu.getSlot(0).getItem().isEmpty(),label+" source slot emptied");
            check(menu.getSlot(1).getItem().getCount()==(physicallyCompatible?total:source.getCount()),label+" server merge or swap uses actual components");
            check(menu.getCarried().getCount()==(physicallyCompatible?0:target.getCount()),label+" native cursor count correct");
            check(menu.getSlot(1).getItem().getCount()+menu.getCarried().getCount()==total,label+" conserved actual amount");
            if(physicallyCompatible) {
                if(label.equals("synthetic-identity-alias-history")) {
                    check(cursorCorrections==0&&fullCorrections==0&&cursor.isEmpty(),label+" candidate removes stale identity alias and delayed cursor");
                    observations.add("synthetic fixture checks repaired session identity alias; fresh localized physical dust origin NOT established");
                } else {
                    check(cursor.isEmpty(),label+" no delayed ghost cursor");
                    check(cursorCorrections==0&&fullCorrections==0,label+" native predicts without redundant rollback");
                }
            }
            check(ItemStack.matches(original,source),label+" original source unchanged");
            check(ItemStack.matches(originalTarget,target),label+" original target unchanged");
            check(ItemStack.isSameItemSameComponents(original,menu.getSlot(1).getItem()),label+" persisted exact identity remains");
        } finally {
            for(String name:List.of("languageCache")) {
                Field field=service.getClass().getDeclaredField(name);field.setAccessible(true);((Map<?,?>)field.get(service)).remove(player.getUUID());
            }
            channel.finishAndReleaseAll();
        }
    }

    private List<Object> drain(EmbeddedChannel channel) {channel.runPendingTasks();List<Object> out=new ArrayList<>();Object msg;while((msg=channel.readOutbound())!=null)out.add(msg);return out;}
    private void check(boolean value,String message) {checks++;if(!value)failures.add(message);}
    private String json(List<String> values) {return values.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ")+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));}
}
