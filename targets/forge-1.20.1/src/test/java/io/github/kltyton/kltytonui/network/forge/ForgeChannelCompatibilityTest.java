package io.github.kltyton.kltytonui.network.forge;

import io.github.kltyton.kltytonui.KltytonUI;
import io.github.kltyton.kltytonui.network.chunk.GenericChunkPacket;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.ServerStatusPing.ChannelData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeChannelCompatibilityTest {
    @Test
    void acceptsServersWithoutKuiAndRequiresCompatiblePeersForNetworkedScreens() {
        // Initialize listener lists through Event in the test JVM, where ModLauncher does not transform these events.
        new NetworkEvent(() -> null).getListenerList();
        new NetworkEvent.GatherLoginPayloadsEvent(List.of(), false).getListenerList();
        new NetworkManagerImpl().registerAnnotated(GenericChunkPacket.class);
        var serverbound = new ResourceLocation(KltytonUI.MODID, "oelib_c2s");
        var clientbound = new ResourceLocation(KltytonUI.MODID, "oelib_s2c");

        assertTrue(NetworkRegistry.checkListPingCompatibilityForClient(Map.of()));
        assertTrue(NetworkRegistry.canConnectToVanillaServer());
        assertTrue(NetworkRegistry.checkListPingCompatibilityForClient(Map.of(
                serverbound, new ChannelData(serverbound, "1", true),
                clientbound, new ChannelData(clientbound, "1", true))));
        assertFalse(NetworkRegistry.checkListPingCompatibilityForClient(Map.of(
                serverbound, new ChannelData(serverbound, "2", true),
                clientbound, new ChannelData(clientbound, "1", true))));
        var requiredByServer = NetworkRegistry.getServerNonVanillaNetworkMods();
        assertTrue(requiredByServer.contains(serverbound.toString()));
        assertTrue(requiredByServer.contains(clientbound.toString()));
    }
}
