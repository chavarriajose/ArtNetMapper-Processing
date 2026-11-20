package artnetmapper;

import java.nio.charset.StandardCharsets;

/**
 * Builds and parses the few Art-Net packets ArtNetMapper needs
 * (ArtDmx, ArtPoll, ArtPollReply). Art-Net 4 layout, protocol version 14.
 */
final class ArtNetPackets {

  static final int PORT = 6454;
  static final int OP_POLL = 0x2000;
  static final int OP_POLL_REPLY = 0x2100;
  static final int OP_DMX = 0x5000;
  static final int DMX_HEADER = 18;

  private static final byte[] ID = "Art-Net\0".getBytes(StandardCharsets.US_ASCII);

  private ArtNetPackets() {}

  /**
   * ArtDmx packet.
   *
   * @param sequence 1..255 (0 disables sequencing on the receiver)
   * @param universe 15-bit port address (Net:SubNet:Universe), 0..32767
   * @param data     DMX slot values, channel 1 at index 0
   * @param length   number of slots to send, 2..512, rounded up to even
   */
  static byte[] artDmx(int sequence, int universe, byte[] data, int length) {
    if (universe < 0 || universe > 0x7FFF) {
      throw new IllegalArgumentException("Art-Net universe must be 0..32767, got " + universe);
    }
    int len = Math.max(2, Math.min(512, length));
    if ((len & 1) == 1) len++;
    byte[] p = new byte[DMX_HEADER + len];
    System.arraycopy(ID, 0, p, 0, 8);
    p[8] = (byte) (OP_DMX & 0xFF);
    p[9] = (byte) (OP_DMX >> 8);
    p[10] = 0;
    p[11] = 14;
    p[12] = (byte) sequence;
    p[13] = 0; // physical
    p[14] = (byte) (universe & 0xFF);        // SubUni
    p[15] = (byte) ((universe >> 8) & 0x7F); // Net
    p[16] = (byte) (len >> 8);
    p[17] = (byte) (len & 0xFF);
    System.arraycopy(data, 0, p, DMX_HEADER, Math.min(len, data.length));
    return p;
  }

  /** ArtPoll packet (14 bytes). */
  static byte[] artPoll() {
    byte[] p = new byte[14];
    System.arraycopy(ID, 0, p, 0, 8);
    p[8] = (byte) (OP_POLL & 0xFF);
    p[9] = (byte) (OP_POLL >> 8);
    p[10] = 0;
    p[11] = 14;
    p[12] = 0; // flags
    p[13] = 0; // diag priority
    return p;
  }

  /** Returns the opcode of an Art-Net packet, or -1 if it is not Art-Net. */
  static int opcode(byte[] p, int length) {
    if (length < 10) return -1;
    for (int i = 0; i < 8; i++) if (p[i] != ID[i]) return -1;
    return (p[8] & 0xFF) | ((p[9] & 0xFF) << 8);
  }

  /** Parsed fields of an ArtPollReply. */
  static final class PollReply {
    final String ip;
    final String shortName;
    final String longName;

    PollReply(String ip, String shortName, String longName) {
      this.ip = ip;
      this.shortName = shortName;
      this.longName = longName;
    }
  }

  /** Parses an ArtPollReply, or returns null if the packet is not one. */
  static PollReply parsePollReply(byte[] p, int length) {
    if (opcode(p, length) != OP_POLL_REPLY || length < 108) return null;
    String ip = (p[10] & 0xFF) + "." + (p[11] & 0xFF) + "." + (p[12] & 0xFF) + "." + (p[13] & 0xFF);
    return new PollReply(ip, cString(p, 26, 18), cString(p, 44, 64));
  }

  /** Reads a null-terminated ASCII string of at most max bytes. */
  static String cString(byte[] p, int offset, int max) {
    int end = offset;
    while (end < offset + max && end < p.length && p[end] != 0) end++;
    return new String(p, offset, end - offset, StandardCharsets.US_ASCII).trim();
  }
}
