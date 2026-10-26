#!/usr/bin/env python3
"""Exercise HOME bounds and every D-pad target using the production navigation table."""
from pathlib import Path
import os, subprocess, tempfile
source=Path(__file__).with_name('bento')/'BentoLayout.java'
harness='''package io.freemytv;
import java.util.*;
public class BentoLayoutCheck {
 public static void main(String[] args) {
  float[][] t=BentoLayout.TILES;
  for(int i=0;i<t.length;i++) {if(i==3)continue;float[] r=t[i];
   if(r[0]<60||r[1]<40||r[2]>1880||r[3]>740||r[2]<=r[0]||r[3]<=r[1])throw new AssertionError("Bounds "+i);
   for(int j=i+1;j<t.length;j++){if(j==3)continue;float[] q=t[j];if(Math.max(r[0],q[0])<Math.min(r[2],q[2])&&Math.max(r[1],q[1])<Math.min(r[3],q[3]))throw new AssertionError("Overlap");}
  }
  for(int start=0;start<9;start++){if(start==3)continue;Set<Integer> seen=new HashSet<>();seen.add(start);for(int step=0;step<9;step++)for(int f:new ArrayList<>(seen))for(int key=19;key<=22;key++){int next=BentoLayout.next(f,key);if(next<0||next>8||next==3)throw new AssertionError("Invalid target");seen.add(next);}if(seen.size()!=8)throw new AssertionError("Unreachable from "+start);}
  if(104+(BentoLayout.DOCK_VISIBLE-1)*190+144>1850)throw new AssertionError("Dock overflows");
  if(BentoLayout.next(8,19)!=2||BentoLayout.next(2,20)!=8)throw new AssertionError("Calendar/dock transition");
  System.out.println("PASS: reference geometry, no overlap, every HOME focus reachable, dock bounds");
 }
}'''
jdk=Path(os.environ['JAVA_HOME'])/'bin'
with tempfile.TemporaryDirectory() as d:
 p=Path(d);(p/'BentoLayoutCheck.java').write_text(harness)
 subprocess.run([str(jdk/'javac'),'-d',d,str(source),str(p/'BentoLayoutCheck.java')],check=True)
 subprocess.run([str(jdk/'java'),'-cp',d,'io.freemytv.BentoLayoutCheck'],check=True)
