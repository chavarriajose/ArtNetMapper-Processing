package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** QC v0.2: probes for reviewer findings. Each test documents current behaviour. */
class QcV02Test {

  private static ArtNetMapper mapper() {
    return new ArtNetMapper(null, "2.0.0.1", false).gamma(1f);
  }

  /** getVal reflects setVal immediately (fixed after QC v0.2). */
  @Test
  void getValReflectsSetValImmediately() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(4, 1);
    f.setVal(0, 200);
    assertEquals(200, f.getVal(0));
    m.sample(null, 0, 0, 1);
    assertEquals(200, f.getVal(0));
  }

  /**
   * setVals on an 8-channel par (to set dimmer + strobe/mode) also pins the color
   * channels by hand, so the canvas link is dead until release().
   */
  @Test
  void setValsPinsColorChannelsToo() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(8, 1).setPos(0, 0).colorChannels(1, 2, 3, 4);
    f.setVals(255, 0, 0, 0, 0, 0, 0, 0); // "dimmer full, rest off"
    m.sample(new int[] {0xFFFF0000}, 1, 1, 1);
    assertEquals(0, m.getUniverse(0)[1], "red stays 0 although canvas is red");
  }

  /** colorChannels() hands its channels to the canvas even if they were set before (fixed after QC v0.2). */
  @Test
  void linkAfterSetFollowsCanvas() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(4, 1).setPos(0, 0);
    f.setVals(0, 0, 0, 77);
    f.colorChannels(0, 1, 2);
    m.sample(new int[] {0xFFFF0000}, 1, 1, 1);
    assertArrayEquals(new int[] {255, 0, 0, 77}, java.util.Arrays.copyOf(m.getUniverse(0), 4));
  }

  /** Huge strip/matrix sizes give the clear address message (fixed after QC v0.2). */
  @Test
  void hugeSizeGivesClearMessage() {
    ArtNetMapper m = mapper();
    IllegalArgumentException t = assertThrows(IllegalArgumentException.class, () -> m.strip(3, 1, Integer.MAX_VALUE));
    assertTrue(t.getMessage().contains("one universe holds 512"), t.getMessage());
    assertThrows(IllegalArgumentException.class, () -> m.matrix(3, 1, 70000, 70000));
  }

  @Test
  void minSpacingAndOutline() {
    ArtNetMapper m = mapper();
    Fixture s = m.strip(3, 1, 3).line(0, 0, 10, 0);
    assertEquals(5, s.minSpacing(), 1e-4);
    assertEquals(Float.POSITIVE_INFINITY, m.fixture(3, 100).setPos(0, 0).minSpacing());
    float[] c = m.matrix(3, 200, 2, 2).rect(0, 0, 10, 10).outline(1);
    assertArrayEquals(new float[] {-1, -1, 11, -1, 11, 11, -1, 11}, c, 1e-4f);
  }

  /** Optional universe in the middle: forgetting it silently shifts meaning, no error. */
  @Test
  void stripArgShiftIsSilent() {
    ArtNetMapper m = mapper();
    Fixture a = m.strip(3, 1, 9, 30);  // universe 1, address 9, 30 px
    Fixture b = m.strip(3, 9, 30);     // address 9, 30 px, universe 0
    assertEquals(1, a.universe);
    assertEquals(0, b.universe);
    Fixture c = m.matrix(3, 2, 8, 8);  // reads as "universe 2, address 8, 8 cols"? -> address 2, 8x8
    assertEquals(2, c.address);
    assertEquals(0, c.universe);
  }

  /** release() also clears the hand flag on unlinked channels; their value stays. */
  @Test
  void releaseKeepsUnlinkedValue() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(5, 1).setPos(0, 0).colorChannels(1, 2, 3);
    f.setVals(200, 9, 9, 9, 50);
    f.release();
    m.sample(new int[] {0xFF0A141E}, 1, 1, 1);
    assertArrayEquals(new int[] {200, 10, 20, 30, 50}, java.util.Arrays.copyOf(m.getUniverse(0), 5));
  }

  /** Color channels at any positions, 3 or 4; brightness/gamma not applied to hand values. */
  @Test
  void anyPositionsAndRawManual() {
    ArtNetMapper m = new ArtNetMapper(null, "2.0.0.1", false).gamma(2.5f).brightness(0.5f);
    Fixture f = m.fixture(6, 1).setPos(0, 0).colorChannels(5, 0, 3, 1).whiteMode(true);
    f.setVal(2, 100);
    m.sample(new int[] {0xFFFFFFFF}, 1, 1, 1);
    int[] c = java.util.Arrays.copyOf(m.getUniverse(0), 6);
    // w = 255 -> gamma 255 -> *0.5 = 128 on ch1; r,g,b = 0
    assertArrayEquals(new int[] {0, 128, 100, 0, 0, 0}, c);
  }
}
