#!/usr/bin/env python3
"""Check the actual Java geometry without Android or a TV."""
from pathlib import Path
import os
import subprocess
import tempfile

source=Path(__file__).with_name('bento')/'BentoLayout.java'
harness='''package io.freemytv;
public class BentoLayoutCheck {
    public static void main(String[] args) {
        float[][] tiles=BentoLayout.TILES;
        for(int i=0;i<tiles.length;i++) {
            float[] r=tiles[i];
            if(r[0]<40 || r[1]<40 || r[2]>1880 || r[3]>820) throw new AssertionError("Tile outside margins");
            if(r[2]-r[0]<2*40 || r[3]-r[1]<2*40) throw new AssertionError("Invalid content area");
            float dx=(r[2]-r[0])*.02f,dy=(r[3]-r[1])*.02f;
            if(r[0]-dx<0 || r[1]-dy<0 || r[2]+dx>1920 || r[3]+dy>1080) throw new AssertionError("Focus outside screen");
            for(int j=i+1;j<tiles.length;j++) {
                float[] s=tiles[j];
                if(Math.max(r[0],s[0])<Math.min(r[2],s[2]) && Math.max(r[1],s[1])<Math.min(r[3],s[3])) throw new AssertionError("Overlapping tiles");
            }
        }
        float[] shelf=BentoLayout.SHELF;
        if(shelf[0]!=40 || shelf[2]!=1880 || 1080-shelf[3]!=40 || shelf[1]-820!=40) throw new AssertionError("Shelf margins");
        if(tiles[2][0]-tiles[0][2]!=24 || tiles[3][0]-tiles[2][2]!=24 || tiles[6][0]-tiles[3][2]!=24) throw new AssertionError("Horizontal gaps");
        if(tiles[1][1]-tiles[0][3]!=24 || tiles[4][1]-tiles[3][3]!=24 || tiles[5][1]-tiles[4][3]!=24) throw new AssertionError("Vertical gaps");
        System.out.println("PASS: 40px outer margins/padding, 24px tile gaps, no overlap, focus inside 1920x1080");
    }
}'''
jdk=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory(prefix='bento-layout-') as directory:
    root=Path(directory)
    (root/'BentoLayoutCheck.java').write_text(harness)
    subprocess.run([str(jdk/'javac'),'-d',str(root),str(source),str(root/'BentoLayoutCheck.java')],check=True)
    subprocess.run([str(jdk/'java'),'-cp',str(root),'io.freemytv.BentoLayoutCheck'],check=True)
