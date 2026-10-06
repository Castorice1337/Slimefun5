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
public final class UpstreamPedestalProbe extends JavaPlugin {
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
        check(Bukkit.getOnlinePlayers().isEmpty(),"isolated no real players");
        server=((CraftServer)Bukkit.getServer()).getServer();
        observations.add("Slimefun version="+Slimefun.instance().getDescription().getVersion());
        altarRoundTrips();
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
            for(int variant=0;variant<4;variant++) {
            var source=SlimefunItem.getById(id).getItem().clone();source.setAmount(32);
            if(variant>0 && variant<3) {
                var display=Slimefun.getItemTranslationService().renderForPacket(id,"zh-CN",io.github.thebusybiscuit.slimefun5.core.services.localization.TranslationConfig.FallbackMode.ENGLISH,true);
                var meta=source.getItemMeta();meta.setDisplayName(display.name);meta.setLore(variant==2?display.lore:null);source.setItemMeta(meta);
            }
            if(variant==3) {
                var meta=source.getItemMeta();meta.setDisplayName("§6Custom specimen");meta.setLore(List.of("custom note","keep this"));
                meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey("pedestal_probe","foreign"),org.bukkit.persistence.PersistentDataType.INTEGER,97);
                meta.setCustomModelData(172);source.setItemMeta(meta);source.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING,1);
            }
            bukkit.getInventory().clear();bukkit.getInventory().setItemInMainHand(source.clone());
            int completed=0;
            for(int i=0;i<4;i++) {
                use.invoke(handler,block,bukkit); // actual place route, consumes one
                var entity=pedestal.getPlacedItem(block);
                check(entity.isPresent(),id+" rapid pedestal item exists round="+i);
                if(entity.isEmpty())break;
                var restored=pedestal.getOriginalItemStack(entity.get());
                if(i==0)observations.add("pedestal "+id+" variant="+variant+" sourceLore="+source.getItemMeta().getLore()+" placedLore="+entity.get().getItemStack().getItemMeta().getLore()+" restoredLore="+restored.getItemMeta().getLore());
                check(restored.isSimilar(source),id+" Bukkit original metadata variant="+variant+" round="+i);
                check(ItemStack.isSameItemSameComponents(CraftItemStack.asNMSCopy(source),CraftItemStack.asNMSCopy(restored)),id+" native original metadata variant="+variant+" round="+i);
                use.invoke(handler,block,bukkit); // actual remove/addItem route
                check(bukkit.getInventory().getItemInMainHand().getAmount()==32,id+" rapid return amount="+i);
                check(bukkit.getInventory().getItemInMainHand().isSimilar(source),id+" rapid return physical metadata variant="+variant+" round="+i);
                check(ItemStack.isSameItemSameComponents(CraftItemStack.asNMSCopy(source),CraftItemStack.asNMSCopy(bukkit.getInventory().getItemInMainHand())),id+" native returned metadata variant="+variant+" round="+i);
                completed++;
            }
            int actualTotal=0;for(var held:bukkit.getInventory().getContents())if(held!=null&&id.equals(Slimefun.getItemDataService().getItemData(held).orElse(null)))actualTotal+=held.getAmount();
            check(actualTotal==32,id+" total32 conserved across slots variant="+variant);
            observations.add("official pedestal right-click rapid place/take "+id+" physicalVariant="+variant+" rounds="+completed+" handCount="+bukkit.getInventory().getItemInMainHand().getAmount()+" inventoryTotal="+actualTotal+" unchangedSource="+ItemStack.isSameItemSameComponents(CraftItemStack.asNMSCopy(source),CraftItemStack.asNMSCopy(bukkit.getInventory().getItemInMainHand())));
            }
        }
        var stand=pedestal.getArmorStand(block,false);if(stand!=null)stand.remove();block.setType(org.bukkit.Material.AIR);
    }

    private void check(boolean value,String message) {checks++;if(!value)failures.add(message);}
    private String json(List<String> values) {return values.stream().map(s->"\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ")+"\"").collect(java.util.stream.Collectors.joining(",","[","]"));}
}
