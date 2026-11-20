package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PacketTest {

  @Test
  void artDmxHeaderMatchesSpec() {
    byte[] data = new byte[512];
    data[0] = (byte) 255;
    data[511] = 7;
    byte[] p = ArtNetPackets.artDmx(3, 0, data, 512);
    assertEquals(18 + 512, p.length);
    assertEquals("Art-Net\0", new String(p, 0, 8, StandardCharsets.US_ASCII));
    assertEquals(0x00, p[8] & 0xFF);  // OpDmx low byte
    assertEquals(0x50, p[9] & 0xFF);  // OpDmx high byte
    assertEquals(0, p[10]);
    assertEquals(14, p[11]);          // protocol version
    assertEquals(3, p[12]);           // sequence
    assertEquals(0x02, p[16] & 0xFF); // length hi
    assertEquals(0x00, p[17] & 0xFF); // length lo
    assertEquals(255, p[18] & 0xFF);  // channel 1
    assertEquals(7, p[18 + 511]);     // channel 512
  }

  @Test
  void artDmxMatchesWorkingCppTest() {
    // Byte-for-byte the header tools/artnet_test.cpp sent to the real node.
    byte[] p = ArtNetPackets.artDmx(1, 0, new byte[512], 512);
    byte[] expected = {'A', 'r', 't', '-', 'N', 'e', 't', 0, 0x00, 0x50, 0, 14, 1, 0, 0, 0, 0x02, 0x00};
    for (int i = 0; i < expected.length; i++) assertEquals(expected[i], p[i], "byte " + i);
  }

  @Test
  void universeIs15BitPortAddress() {
    byte[] p = ArtNetPackets.artDmx(1, 0x1234, new byte[512], 512);
    assertEquals(0x34, p[14] & 0xFF); // SubUni
    assertEquals(0x12, p[15] & 0xFF); // Net
    byte[] max = ArtNetPackets.artDmx(1, 32767, new byte[512], 512);
    assertEquals(0xFF, max[14] & 0xFF);
    assertEquals(0x7F, max[15] & 0xFF);
    assertThrows(IllegalArgumentException.class, () -> ArtNetPackets.artDmx(1, 32768, new byte[512], 512));
    assertThrows(IllegalArgumentException.class, () -> ArtNetPackets.artDmx(1, -1, new byte[512], 512));
  }

  @Test
  void oddLengthRoundsUpToEven() {
    byte[] p = ArtNetPackets.artDmx(1, 0, new byte[512], 7);
    assertEquals(8, ((p[16] & 0xFF) << 8) | (p[17] & 0xFF));
    assertEquals(18 + 8, p.length);
  }

  @Test
  void artPollLayout() {
    byte[] p = ArtNetPackets.artPoll();
    assertEquals(14, p.length);
    assertEquals(ArtNetPackets.OP_POLL, ArtNetPackets.opcode(p, p.length));
    assertEquals(14, p[11]);
  }

  @Test
  void parsesPollReplyWithNullTerminatedNames() {
    byte[] p = new byte[239];
    System.arraycopy("Art-Net\0".getBytes(StandardCharsets.US_ASCII), 0, p, 0, 8);
    p[8] = 0x00;
    p[9] = 0x21;
    p[10] = 2; p[11] = 0; p[12] = 0; p[13] = 1;
    byte[] shortName = "Node\0garbage".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(shortName, 0, p, 26, shortName.length);
    byte[] longName = "My DMX Node".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(longName, 0, p, 44, longName.length);
    ArtNetPackets.PollReply r = ArtNetPackets.parsePollReply(p, p.length);
    assertNotNull(r);
    assertEquals("2.0.0.1", r.ip);
    assertEquals("Node", r.shortName);
    assertEquals("My DMX Node", r.longName);
  }

  @Test
  void rejectsNonArtNet() {
    byte[] junk = "hello world, not artnet at all".getBytes(StandardCharsets.US_ASCII);
    assertEquals(-1, ArtNetPackets.opcode(junk, junk.length));
    assertNull(ArtNetPackets.parsePollReply(junk, junk.length));
    assertNull(ArtNetPackets.parsePollReply(ArtNetPackets.artPoll(), 14));
  }
}
