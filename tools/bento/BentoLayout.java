package io.freemytv;

/** Logical 1920x1080 coordinates. Shared by painting, focus and hit testing. */
final class BentoLayout {
    private BentoLayout() {}
    static final float WIDTH = 1920, HEIGHT = 1080;
    static final float MARGIN = 40, GAP = 24, PADDING = 40, RADIUS = 36;
    // clock, weather, board, live TV, inputs, settings, exchange rates, weekly quote
    static final float[][] TILES = {
        {40,40,420,552}, {40,576,420,820}, {444,40,1188,820},
        {1212,40,1512,284}, {1212,308,1512,552}, {1212,576,1512,820},
        {1536,40,1880,284}, {1536,308,1880,820}
    };
    static final float[] SHELF = {40,860,1880,1040};
}
