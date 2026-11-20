package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscoveryTest {

  @Test
  void parsesMacArp() {
    String arp = String.join("\n",
        "? (2.0.0.1) at 2:b6:c0:be:4d:0 on en6 ifscope [ethernet]",
        "? (2.0.0.255) at ff:ff:ff:ff:ff:ff on en6 ifscope [ethernet]",
        "? (192.168.1.1) at a4:2b:b0:11:22:33 on en0 ifscope [ethernet]",
        "? (192.168.1.40) at (incomplete) on en0 ifscope [ethernet]");
    Map<String, Set<String>> m = Discovery.parseArp(arp);
    assertEquals(Set.of("2.0.0.1"), m.get("en6"));
    assertEquals(Set.of("192.168.1.1"), m.get("en0"));
  }

  @Test
  void parsesLinuxArp() {
    Map<String, Set<String>> m = Discovery.parseArp("? (2.0.0.1) at 02:b6:c0:be:4d:00 [ether] on usb0");
    assertEquals(Set.of("2.0.0.1"), m.get("usb0"));
  }

  @Test
  void parsesMacDefaultRoutes() {
    String netstat = String.join("\n",
        "Routing tables",
        "Destination        Gateway            Flags               Netif Expire",
        "default            192.168.1.1        UGScg                 en0",
        "2/24               link#22            UCS                   en6      !");
    Set<String> ifaces = new LinkedHashSet<>();
    Set<String> gws = new LinkedHashSet<>();
    Discovery.parseMacRoutes(netstat, ifaces, gws);
    assertEquals(Set.of("en0"), ifaces);
    assertEquals(Set.of("192.168.1.1"), gws);
  }

  @Test
  void subnetMath() throws Exception {
    InetAddress a = InetAddress.getByName("2.0.0.2");
    assertTrue(Discovery.sameSubnet(a, InetAddress.getByName("2.0.0.1"), 24));
    assertFalse(Discovery.sameSubnet(a, InetAddress.getByName("2.0.1.1"), 24));
    assertTrue(Discovery.sameSubnet(a, InetAddress.getByName("2.200.3.4"), 8));
  }

  @Test
  void rejectsBadIp() {
    assertNull(Discovery.parse("not-an-ip"));
    assertNull(Discovery.parse("example.com"));
    assertThrows(IllegalArgumentException.class, () -> new ArtNetMapper(null, "localhost", false));
  }
}
