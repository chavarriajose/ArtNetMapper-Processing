package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FixtureTest {

  /** Mapper with no sketch and no network, for pure logic tests. */
  private static ArtNetMapper mapper() {
    return new ArtNetMapper(null, "2.0.0.1", false).gamma(1f);
  }

  private static int[] ch(ArtNetMapper m, int universe, int from, int to) {
    return Arrays.copyOfRange(m.getUniverse(universe), from - 1, to);
  }

  @Test
  void newFixtureIsAllZeroAndManual() {
    ArtNetMapper m = mapper();
    m.fixture(4, 1).setPos(0, 0);
    m.sample(new int[] {0xFFFFFFFF}, 1, 1, 1);
    assertArrayEquals(new int[] {0, 0, 0, 0}, ch(m, 0, 1, 4));
  }

  @Test
  void setValIsRawAndClamped() {
    ArtNetMapper m = mapper().gamma(2.5f).brightness(0.5f);
    Fixture f = m.fixture(3, 1);
    f.setVal(0, 200).setVal(1, 300).setVal(2, -5);
    m.sample(null, 0, 0, 1);
    assertArrayEquals(new int[] {200, 255, 0}, ch(m, 0, 1, 3)); // no gamma, no brightness
    f.setVal(0, 127.6f); // float, e.g. red(c)
    m.sample(null, 0, 0, 1);
    assertEquals(128, m.getUniverse(0)[0]);
    assertEquals(128, f.getVal(0));
  }

  @Test
  void setValsSetsFromChannelZero() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(6, 10);
    f.setVals(255, 0, 7);
    m.sample(null, 0, 0, 1);
    assertArrayEquals(new int[] {255, 0, 7, 0, 0, 0}, ch(m, 0, 10, 15));
    assertThrows(IllegalArgumentException.class, () -> f.setVals(1, 2, 3, 4, 5, 6, 7));
  }

  @Test
  void channelRangeChecked() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(4, 1);
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> f.setVal(4, 1));
    assertTrue(e.getMessage().contains("numbered 0-3"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> f.setVal(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> f.colorChannels(0, 1, 4));
    assertThrows(IllegalArgumentException.class, () -> f.colorChannels(0, 1));
    assertThrows(IllegalArgumentException.class, () -> f.colorChannels(0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> m.fixture(0, 1));
  }

  @Test
  void eightChannelModeWithColorChannels() {
    // dimmer r g b w strobe mode speed
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(8, 1).setPos(0, 0).colorChannels(1, 2, 3, 4);
    f.setVal(0, 255).setVal(6, 200);
    m.sample(new int[] {0xFFC86432}, 1, 1, 1); // r200 g100 b50 -> w=50
    assertArrayEquals(new int[] {255, 150, 50, 0, 50, 0, 200, 0}, ch(m, 0, 1, 8));
  }

  @Test
  void rgbOnlyHasNoWhite() {
    ArtNetMapper m = mapper();
    m.fixture(3, 1).setPos(0, 0).colorChannels(0, 1, 2);
    m.sample(new int[] {0xFFFFFFFF}, 1, 1, 1);
    assertArrayEquals(new int[] {255, 255, 255}, ch(m, 0, 1, 3));
  }

  @Test
  void whiteModeOffLeavesWAtZero() {
    ArtNetMapper m = mapper();
    m.fixture(4, 1).setPos(0, 0).colorChannels(0, 1, 2, 3).whiteMode(false);
    m.sample(new int[] {0xFFC86432}, 1, 1, 1);
    assertArrayEquals(new int[] {200, 100, 50, 0}, ch(m, 0, 1, 4));
  }

  @Test
  void manualValueWinsUntilRelease() {
    ArtNetMapper m = mapper();
    Fixture f = m.fixture(4, 1).setPos(0, 0).colorChannels(0, 1, 2, 3);
    int[] green = {0xFF00FF00};
    f.setVals(255, 0, 0, 0);
    m.sample(green, 1, 1, 1);
    assertArrayEquals(new int[] {255, 0, 0, 0}, ch(m, 0, 1, 4));
    m.sample(green, 1, 1, 1); // still holds next frame
    assertArrayEquals(new int[] {255, 0, 0, 0}, ch(m, 0, 1, 4));
    f.release(0);
    m.sample(green, 1, 1, 1);
    assertArrayEquals(new int[] {0, 0, 0, 0}, ch(m, 0, 1, 4)); // ch0 follows canvas (red=0), ch1 still manual 0
    f.release();
    m.sample(green, 1, 1, 1);
    assertArrayEquals(new int[] {0, 255, 0, 0}, ch(m, 0, 1, 4));
  }

  @Test
  void colorChannelsCorrectedManualNot() {
    ArtNetMapper m = mapper().gamma(2.5f).brightness(0.5f);
    Fixture f = m.fixture(4, 1).setPos(0, 0).colorChannels(0, 1, 2);
    f.setVal(3, 128);
    m.sample(new int[] {0xFF808080}, 1, 1, 1);
    int expected = Math.round(ColorMath.gammaTable(2.5f)[128] * 0.5f);
    assertArrayEquals(new int[] {expected, expected, expected, 128}, ch(m, 0, 1, 4));
  }

  @Test
  void stripSetValAllAndPixel() {
    ArtNetMapper m = mapper();
    Fixture s = m.strip(3, 1, 4).line(0, 0, 3, 0).colorChannels(0, 1, 2);
    int[] blue = {0xFF0000FF, 0xFF0000FF, 0xFF0000FF, 0xFF0000FF};
    s.pixel(2).setVals(255, 0, 0);
    m.sample(blue, 4, 1, 1);
    assertArrayEquals(new int[] {0, 0, 255, 0, 0, 255, 255, 0, 0, 0, 0, 255}, ch(m, 0, 1, 12));
    assertEquals(255, s.pixel(2).getVal(0));
    s.pixel(2).release();
    s.setVal(1, 9); // green = 9 on all pixels
    m.sample(blue, 4, 1, 1);
    assertArrayEquals(new int[] {0, 9, 255, 0, 9, 255, 0, 9, 255, 0, 9, 255}, ch(m, 0, 1, 12));
    assertThrows(IllegalArgumentException.class, () -> s.pixel(4));
    assertThrows(IllegalArgumentException.class, () -> s.pixel(4).x());
  }

  @Test
  void pastAddress512Throws() {
    ArtNetMapper m = mapper();
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> m.matrix(3, 0, 400, 8, 8));
    assertTrue(e.getMessage().contains("matrix at DMX 400 needs 192 channels, but a universe ends at 512. Use DMX address 321 or lower"), e.getMessage());
    IllegalArgumentException big = assertThrows(IllegalArgumentException.class, () -> m.matrix(3, 0, 1, 16, 16));
    assertTrue(big.getMessage().contains("needs 768 channels, but one universe holds 512"), big.getMessage());
    assertThrows(IllegalArgumentException.class, () -> m.strip(3, 0, 511, 1)); // 511-513
  }

  @Test
  void exactFitAtEndOfUniverse() {
    ArtNetMapper m = mapper();
    assertEquals(510, m.strip(3, 0, 1, 170).lastAddress());
    assertEquals(512, m.fixture(2, 511).lastAddress());
    assertThrows(IllegalArgumentException.class, () -> m.fixture(3, 511));
    assertThrows(IllegalArgumentException.class, () -> m.fixture(3, 0));
    assertThrows(IllegalArgumentException.class, () -> m.fixture(3, -1, 1));
  }

  @Test
  void overlapOnlyWarns() {
    ArtNetMapper m = mapper();
    PrintStream old = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf));
    try {
      m.fixture(4, 1);
      m.fixture(4, 3);
      m.fixture(4, 1, 3); // other universe: no warning
    } finally {
      System.setOut(old);
    }
    String out = buf.toString();
    assertEquals(1, out.split("warning", -1).length - 1, out);
    assertEquals(3, m.fixtures().size());
  }

  @Test
  void unplacedLinkedFixtureWarnsOnceManualDoesNot() {
    ArtNetMapper m = mapper();
    m.fixture(3, 1);                              // manual, unplaced: fine
    m.strip(3, 10, 4).colorChannels(0, 1, 2);     // linked, unplaced: warn
    PrintStream old = System.out;
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(buf));
    try {
      m.sample(new int[100], 10, 10, 1);
      m.sample(new int[100], 10, 10, 1);
    } finally {
      System.setOut(old);
    }
    String out = buf.toString();
    assertEquals(1, out.split("has no position", -1).length - 1, out);
    assertTrue(out.contains("strip") && out.contains("line(x1, y1, x2, y2)"), out);
  }

  @Test
  void lineSpreadsFromFirstToLast() {
    ArtNetMapper m = mapper();
    Fixture s = m.strip(3, 1, 5).line(0, 10, 100, 10);
    assertEquals(0, s.pixel(0).x(), 1e-4);
    assertEquals(25, s.pixel(1).x(), 1e-4);
    assertEquals(100, s.pixel(4).x(), 1e-4);
    assertEquals(25, s.pixel(1).x(), 1e-4);
  }

  @Test
  void lineToWidthReadsEdgePixel() {
    ArtNetMapper m = mapper();
    m.strip(3, 1, 3).line(0, 5, 100, 5).colorChannels(0, 1, 2);
    int[] px = new int[100 * 10];
    Arrays.fill(px, 0xFFFFFFFF);
    m.sample(px, 100, 10, 1);
    assertArrayEquals(new int[] {255, 255, 255, 255, 255, 255, 255, 255, 255}, ch(m, 0, 1, 9));
  }

  @Test
  void rectOnlyForMatrix() {
    ArtNetMapper m = mapper();
    Fixture p = m.fixture(3, 1);
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> p.rect(0, 0, 10, 10));
    assertTrue(e.getMessage().contains("Place this fixture with setPos(x, y)"), e.getMessage());
    assertThrows(IllegalStateException.class, () -> p.rotate(1));
  }

  @Test
  void placingAnotherWayLeavesTheGrid() {
    ArtNetMapper m = mapper();
    Fixture mx = m.matrix(3, 1, 2, 2).rect(0, 0, 100, 100);
    mx.setPos(7, 7);
    mx.rotate(1f);              // must not snap back to the old grid
    assertEquals(7, mx.pixel(0).x(), 1e-4);
    assertFalse(mx.hasRect);
  }

  @Test
  void infoGetters() {
    ArtNetMapper m = mapper();
    Fixture s = m.strip(3, 1, 9, 30);
    assertEquals(9, s.address());
    assertEquals(98, s.lastAddress());
    assertEquals(1, s.universe());
    assertEquals(3, s.channels());
    assertEquals(30, s.pixels());
    assertEquals(90, s.totalChannels());
    Fixture p = m.fixture(4, 1).setPos(12, 34).sampleRadius(2).whiteMode(false);
    assertEquals(12, p.x(), 1e-4);
    assertEquals(34, p.y(), 1e-4);
    assertEquals(2, p.getSampleRadius());
    assertFalse(p.getWhiteMode());
    assertEquals(1f, p.getGamma(), 1e-6);        // follows the global gamma (1 in these tests)
    m.gamma(2.2f);
    assertEquals(2.2f, p.getGamma(), 1e-6);
    p.gamma(1.8f);
    assertEquals(1.8f, p.getGamma(), 1e-6);
    m.brightness(0.4f).maxRate(30);
    assertEquals(0.4f, m.getBrightness(), 1e-6);
    assertEquals(30f, m.getMaxRate(), 1e-6);
  }

  @Test
  void toStringSaysWhatAndWhere() {
    ArtNetMapper m = mapper();
    assertEquals("strip, 30 pixels x 3 channels, universe 1, DMX 9-98", m.strip(3, 1, 9, 30).toString());
    assertEquals("fixture, 1 channel, universe 0, DMX 1-1", m.fixture(1, 1).toString());
    assertEquals("matrix, 2x3 pixels x 3 channels, universe 0, DMX 100-117", m.matrix(3, 100, 2, 3).toString());
  }
}
