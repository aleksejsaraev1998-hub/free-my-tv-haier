#!/usr/bin/env python3
"""Fail the build if a preview regains system integration or a native ABI dependency."""
import argparse
from pathlib import Path
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile
import re

p = argparse.ArgumentParser()
p.add_argument('source', type=Path)
p.add_argument('--profile', choices=['standard', 'lite', 'bento', 'bento-home'], required=True)
p.add_argument('--output', type=Path, required=True)
args = p.parse_args()
root = args.source.resolve()
args.output.mkdir(parents=True, exist_ok=True)
is_home = args.profile == 'bento-home'
package = 'io.freemytv.haierpreview.home' if is_home else 'io.freemytv.haierpreview' + ('.' + args.profile if args.profile != 'standard' else '')
apk = root / 'FreeMyTV.apk'
bt = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0'
ns = '{http://schemas.android.com/apk/res/android}'
manifest = ET.parse(root / 'app/AndroidManifest.xml').getroot()
assert manifest.get('package') == package, 'Unexpected package'
assert {e.get(ns+'name') for e in manifest.findall('uses-permission')} == {
    'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE'}, 'Unexpected permission'
assert not manifest.findall('.//receiver'), 'Preview must not receive boot/update broadcasts'
assert is_home == any(e.get(ns+'name') == 'android.intent.category.HOME' for e in manifest.findall('.//category')), 'Unexpected HOME role'
with zipfile.ZipFile(apk) as archive:
    assert archive.read('classes.dex').startswith(b'dex\n'), 'Missing Dex payload'
    assert not any(n.startswith('lib/') or n.endswith(('.so','.keystore','.properties')) for n in archive.namelist()), 'Unexpected native code or private config'
def command(*cmd):
    return subprocess.check_output([str(c) for c in cmd], text=True, stderr=subprocess.STDOUT)
signature = command(bt/'apksigner', 'verify', '--verbose', '--print-certs', apk)
badging = command(bt/'aapt', 'dump', 'badging', apk)
binary_manifest = command(bt/'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
assert "package: name='" + package + "'" in badging
assert "sdkVersion:'26'" in badging and "targetSdkVersion:'33'" in badging
assert 'native-code:' not in badging
assert ('android.intent.category.HOME' in binary_manifest) == is_home, 'Compiled HOME mismatch'
for forbidden in (['BOOT_COMPLETED', 'MY_PACKAGE_REPLACED'] if is_home else ['android.intent.category.HOME', 'BOOT_COMPLETED', 'MY_PACKAGE_REPLACED']):
    assert forbidden not in binary_manifest, 'Unsafe compiled manifest: ' + forbidden
# Check compiled Java as well as source: each shell entry point must throw immediately.
javap = Path(os.environ['JAVA_HOME']) / 'bin/javap'
bytecode = command(javap, '-classpath', root/'out/classes', '-c', package+'.Adb')
assert bytecode.count('athrow') == 2 and 'java/net/' not in bytecode
assert 'Haier Preview: shell is disabled.' in bytecode
assert 'Haier Preview: system changes are disabled.' in bytecode
if args.profile in ('lite', 'bento', 'bento-home'):
    scene = command(javap, '-classpath', root/'out/classes', '-c', package+'.Scene')
    assert not re.search(r'\bnew\b[^\n]*// class android/graphics/RuntimeShader', scene), 'Lite shader constructor restored'
    (args.output/'lite-scene-bytecode.txt').write_text(scene)
if args.profile in ('bento', 'bento-home'):
    home = command(javap, '-classpath', root/'out/classes', '-c', package+'.HomeActivity')
    assert 'class ' + package.replace('.','/')+'/BentoView' in home
    assert 'class ' + package.replace('.','/')+'/HomeView' not in home
    view = command(javap, '-classpath', root/'out/classes', '-c', package+'.BentoView')
    draw = view.split('protected void onDraw(android.graphics.Canvas);',1)[1].split('boolean onKey(',1)[0]
    assert not re.search(r'\b(new|anewarray|newarray)\b', draw), 'Allocation in Bento onDraw'
    assert 'setLayerType' not in view and 'android/webkit/WebView' not in view
    month = command(javap, '-classpath', root/'out/classes', '-c', '-p', package+'.MonthPanel')
    paint = month.split('void draw(android.graphics.Canvas, boolean);',1)[1].split('java.time.LocalDate hit(',1)[0]
    assert not re.search(r'\b(new|anewarray|newarray)\b', paint), 'Allocation in month painting'
    assert 'CalendarMonth' in month
    protocol = command(javap, '-classpath', root/'out/classes', '-c', '-p', package+'.CalendarProtocol')
    assert 'String PROPFIND' in protocol and 'String REPORT' in protocol and 'String GET' in protocol
    assert not re.search(r'String (PUT|POST|DELETE|MKCALENDAR|PROPPATCH)\b', protocol), 'Calendar write method introduced'
    recurrence = command(javap, '-classpath', root/'out/classes', '-c', '-p', package+'.CalendarRecurrence')
    assert 'org/dmfs/rfc5545/recur/RecurrenceRule' in recurrence
    assert manifest.find('application').get(ns+'allowBackup') == 'false'
    with zipfile.ZipFile(apk) as payload:
        assert b'Lorg/dmfs/rfc5545/recur/RecurrenceRule;' in payload.read('classes.dex')
        assert len([n for n in payload.namelist() if n.startswith('assets/calendar-licenses/')]) == 6
    (args.output/'bento-view-bytecode.txt').write_text(view)
for name, text in [('signature.txt', signature), ('apk-info.txt', badging),
                   ('manifest-tree.txt', binary_manifest), ('adb-bytecode.txt', bytecode)]:
    (args.output/name).write_text(text)
print('PASS: isolated package, controlled HOME variant, no boot hooks, native independence, signature, compiled ADB guard')
