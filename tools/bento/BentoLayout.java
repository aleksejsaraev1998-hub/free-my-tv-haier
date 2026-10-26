package io.freemytv;

/** Logical reference geometry shared by hit testing and D-pad navigation. */
final class BentoLayout {
    private BentoLayout() {}
    static final float WIDTH=1920,HEIGHT=1080;
    static final int DOCK_VISIBLE=9;
    // header settings, weather, calendar + agenda, reserved TV, HDMI, settings, network, Bluetooth
    static final float[][] TILES={
        {1778,60,1862,146},{1230,212,1850,464},{72,212,1202,722},
        {0,0,0,0},{1230,490,1542,722},{1566,490,1850,722},
        {1538,60,1654,146},{1674,60,1758,146}
    };
    static final float[] SHELF={72,778,1850,1028};
    // Key codes UP, DOWN, LEFT, RIGHT. Every visible area is reachable from the dock.
    static final int[][] NEXT={
        {0,1,7,0},{6,4,2,1},{6,8,2,1},{2,8,2,4},
        {1,8,2,5},{1,8,4,5},{6,1,2,7},{7,1,6,0},{2,8,8,8}
    };
    static int next(int focus,int key){return NEXT[focus][key-19];}
}
