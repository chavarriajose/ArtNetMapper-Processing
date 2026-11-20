package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MappingTest {

  private static ArtNetMapper mapper() {
    return new ArtNetMapper(null, "2.0.0.1", false);
  }

  private static int[] cell(int i, int cols, int rows, int wiring, int corner, int order) {
    int[] out = new int[2];
    Fixture.gridPosition(i, cols, rows, wiring, corner, order, out);
    return out;
  }

  @Test
  void progressiveRowsTopLeft() {
    assertArrayEquals(new int[] {0, 0}, cell(0, 4, 3, ArtNetMapper.PROGRESSIVE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS));
    assertArrayEquals(new int[] {3, 0}, cell(3, 4, 3, ArtNetMapper.PROGRESSIVE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS));
    assertArrayEquals(new int[] {0, 1}, cell(4, 4, 3, ArtNetMapper.PROGRESSIVE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS));
  }

  @Test
  void serpentineRowsReverseOddRows() {
    assertArrayEquals(new int[] {3, 1}, cell(4, 4, 3, ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS));
    assertArrayEquals(new int[] {0, 1}, cell(7, 4, 3, ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS));
    assertArrayEquals(new int[] {0, 2}, cell(8, 4, 3, ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS));
  }

  @Test
  void columnsAndCorners() {
    // columns, progressive, top-left: element 1 is below element 0
    assertArrayEquals(new int[] {0, 1}, cell(1, 4, 3, ArtNetMapper.PROGRESSIVE, ArtNetMapper.TOP_LEFT, ArtNetMapper.COLUMNS));
    // columns, serpentine: second column runs bottom to top
    assertArrayEquals(new int[] {1, 2}, cell(3, 4, 3, ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.COLUMNS));
    // start corners
    assertArrayEquals(new int[] {3, 0}, cell(0, 4, 3, ArtNetMapper.PROGRESSIVE, ArtNetMapper.TOP_RIGHT, ArtNetMapper.ROWS));
    assertArrayEquals(new int[] {0, 2}, cell(0, 4, 3, ArtNetMapper.PROGRESSIVE, ArtNetMapper.BOTTOM_LEFT, ArtNetMapper.ROWS));
    assertArrayEquals(new int[] {3, 2}, cell(0, 4, 3, ArtNetMapper.PROGRESSIVE, ArtNetMapper.BOTTOM_RIGHT, ArtNetMapper.ROWS));
  }

  @Test
  void every16WiringIsAPermutation() {
    int cols = 5, rows = 4;
    for (int w = 0; w <= 1; w++)
      for (int c = 0; c <= 3; c++)
        for (int o = 0; o <= 1; o++) {
          boolean[] seen = new boolean[cols * rows];
          for (int i = 0; i < cols * rows; i++) {
            int[] p = cell(i, cols, rows, w, c, o);
            int k = p[1] * cols + p[0];
            assertFalse(seen[k], "duplicate cell w" + w + " c" + c + " o" + o);
            seen[k] = true;
          }
        }
  }

  @Test
  void rectPlacesCentersAndRotateAboutCenter() {
    ArtNetMapper m = mapper();
    Fixture mx = m.matrix(m.type("r, g, b"), 1, 2, 2).rect(0, 0, 100, 100);
    assertEquals(25, mx.x(0), 1e-3);
    assertEquals(25, mx.y(0), 1e-3);
    assertEquals(75, mx.x(1), 1e-3);
    mx.rotate((float) Math.PI); // 180 degrees: element 0 moves to bottom-right
    assertEquals(75, mx.x(0), 1e-3);
    assertEquals(75, mx.y(0), 1e-3);
  }

  @Test
  void samplingWritesChannelsWithWhiteGammaAndConstants() {
    ArtNetMapper m = mapper().gamma(1f);
    FixtureType par = m.type("dim=255, r, g, b, w, strobe=0");
    m.fixture(par, 1).at(0, 0);
    m.fixture(m.type("r, g, b, w"), 7).at(1, 0).whiteMode(false);
    int[] pixels = {0xFFC86432, 0xFFC86432}; // r200 g100 b50
    m.sample(pixels, 2, 1, 1);
    int[] ch = m.channels(0);
    assertArrayEquals(new int[] {255, 150, 50, 0, 50, 0}, java.util.Arrays.copyOfRange(ch, 0, 6));
    assertArrayEquals(new int[] {200, 100, 50, 0}, java.util.Arrays.copyOfRange(ch, 6, 10));
  }

  @Test
  void perFixtureGammaOverridesGlobal() {
    ArtNetMapper m = mapper(); // global 2.5
    m.fixture(m.type("r, g, b"), 1).at(0, 0).gamma(1f);
    m.fixture(m.type("r, g, b"), 4).at(0, 0);
    m.sample(new int[] {0xFF808080}, 1, 1, 1);
    int[] ch = m.channels(0);
    assertEquals(128, ch[0]);
    assertEquals(ColorMath.gammaTable(2.5f)[128], ch[3]);
  }

  @Test
  void sampleRadiusAveragesAndOutsideIsBlack() {
    int[] px = new int[9];
    px[4] = 0xFFFFFFFF; // white center of 3x3, rest black
    assertEquals(0xFFFFFF, ArtNetMapper.samplePoint(px, 3, 3, 1, 1, 1, 0));
    assertEquals((255 / 9) * 0x010101, ArtNetMapper.samplePoint(px, 3, 3, 1, 1, 1, 1));
    assertEquals(0, ArtNetMapper.samplePoint(px, 3, 3, 1, 10, 10, 0));
  }

  @Test
  void pixelDensityScalesCoordinates() {
    int[] px = new int[16]; // 4x4 physical pixels, sketch is 2x2 at density 2
    px[2 * 4 + 2] = 0xFF00FF00;
    assertEquals(0x00FF00, ArtNetMapper.samplePoint(px, 4, 4, 2, 1, 1, 0));
  }
}
