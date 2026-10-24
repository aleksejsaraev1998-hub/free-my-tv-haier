#!/usr/bin/env python3
"""Prepare an isolated, non-switching preview from the reviewed upstream revision."""
from pathlib import Path
import subprocess
import sys
import json
import re
import xml.etree.ElementTree as ET
import argparse

PIN = 'aa34335d25fa2224053d1bb16851e4522a6bbeb0'
parser = argparse.ArgumentParser()
parser.add_argument('source')
parser.add_argument('--profile', choices=['standard', 'lite'], default='standard')
args = parser.parse_args()
lite = args.profile == 'lite'
PACKAGE = 'io.freemytv.haierpreview' + ('.lite' if lite else '')
label = 'Free My TV Haier ' + ('Lite Preview' if lite else 'Preview')
root = Path(args.source).resolve()
head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
if head != PIN:
    raise SystemExit('Unreviewed upstream revision; refusing to patch')
if subprocess.run(['git', '-C', str(root), 'diff', '--quiet']).returncode != 0:
    raise SystemExit('Source has tracked changes; use a clean checkout')
for path in [root / 'build.sh', *root.glob('app/**/*.java'), *root.glob('app/**/*.xml')]:
    path.write_text(path.read_text().replace('io.freemytv', PACKAGE))

# Remove unused ADB-only translation entries with the transport.
adb_path = root / 'app/src/io/freemytv/Adb.java'
adb_keys = re.findall(r'Ui\.tr\("([^"]+)"\)', adb_path.read_text())
other_code = '\n'.join(p.read_text() for p in root.glob('app/**/*.java') if p != adb_path)
for catalogue in root.glob('app/assets/i18n/*.json'):
    data = json.loads(catalogue.read_text())
    for key in adb_keys:
        if 'Ui.tr("' + key + '")' not in other_code:
            data.pop(key, None)
    catalogue.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
# Remove the transport, rather than relying on a hidden UI switch.
(root / 'app/src/io/freemytv/Adb.java').write_text('''package io.freemytv.haierpreview;
import android.content.Context;
import java.io.Closeable;
import java.io.IOException;
final class Adb implements Closeable {
    interface Approval { void waiting(); }
    private Adb() {}
    static Adb connect(Context c, int approvalMs, Approval approval) throws IOException {
        throw new IOException("Haier Preview: system changes are disabled.");
    }
    String shell(String command) throws IOException {
        throw new IOException("Haier Preview: shell is disabled.");
    }
    @Override public void close() {}
}
'''.replace('io.freemytv.haierpreview', PACKAGE))

def replace_once(name, before, after):
    path = root / 'app/src/io/freemytv' / name
    text = path.read_text()
    if text.count(before) != 1:
        raise SystemExit('Patch anchor changed: ' + name)
    path.write_text(text.replace(before, after))

(root / 'app/src/io/freemytv/HaierProfile.java').write_text(
    'package ' + PACKAGE + ';\nfinal class HaierProfile {\n'
    + '    static final boolean LITE = ' + str(lite).lower() + ';\n}\n')
replace_once('Scene.java', '        try {\n            aurora = new RuntimeShader',
             '        if (!HaierProfile.LITE) try {\n            aurora = new RuntimeShader')
replace_once('HomeView.java', 'if (busy) Choreographer.getInstance().postFrameCallback(this);',
             'if (busy) {\n'
             '            if (HaierProfile.LITE) Choreographer.getInstance().postFrameCallbackDelayed(this, 33);\n'
             '            else Choreographer.getInstance().postFrameCallback(this);\n'
             '        }')
replace_once('SettingsActivity.java',
             'if (moving || SystemClock.uptimeMillis() - lastSky >= 100) invalidate();\n'
             '            Choreographer.getInstance().postFrameCallback(this);',
             'if (moving || SystemClock.uptimeMillis() - lastSky >= (HaierProfile.LITE ? 1000 : 100)) invalidate();\n'
             '            if (HaierProfile.LITE) Choreographer.getInstance().postFrameCallbackDelayed(this, moving ? 33 : 1000);\n'
             '            else Choreographer.getInstance().postFrameCallback(this);')
replace_once('DreamView.java',
             '        Choreographer.getInstance().postFrameCallback(this);\n    }\n\n    /** The clock that drives',
             '        if (HaierProfile.LITE) Choreographer.getInstance().postFrameCallbackDelayed(this, 100);\n'
             '        else Choreographer.getInstance().postFrameCallback(this);\n    }\n\n    /** The clock that drives')
replace_once('SettingsActivity.java', '        boolean onKey(KeyEvent e) {',
             '        boolean onKey(KeyEvent e) {\n'
             '            if (HaierProfile.LITE && running) {\n'
             '                Choreographer.getInstance().removeFrameCallback(this);\n'
             '                Choreographer.getInstance().postFrameCallback(this);\n'
             '            }')
replace_once('SettingsActivity.java', 'focus = id - 1;\n                    invalidate();',
             'focus = id - 1;\n'
             '                    if (HaierProfile.LITE && running) {\n'
             '                        Choreographer.getInstance().removeFrameCallback(SettingsView.this);\n'
             '                        Choreographer.getInstance().postFrameCallback(SettingsView.this);\n'
             '                    }\n                    invalidate();')
replace_once('Backdrop.java',
             'return longest > 0 ? Math.min(1f, longest / 1920f) : 1f;',
             'float limit = HaierProfile.LITE ? 2f / 3f : 1f;\n'
             '        return longest > 0 ? Math.min(limit, longest / 1920f) : limit;')
replace_once('HomeData.java', 'WEATHER_EVERY = 30 * 60_000L;',
             'WEATHER_EVERY = (HaierProfile.LITE ? 60 : 30) * 60_000L;')
ns = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ns)
a = lambda name: '{' + ns + '}' + name
manifest = root / 'app/AndroidManifest.xml'
tree = ET.parse(manifest)
m = tree.getroot()
m.set(a('versionCode'), '3')
m.set(a('versionName'), '1.1-haier-' + args.profile + '-preview.2')
app = m.find('application')
app.set(a('label'), label)
for permission in list(m.findall('uses-permission')):
    if permission.get(a('name')) == 'android.permission.RECEIVE_BOOT_COMPLETED':
        m.remove(permission)
for receiver in list(app.findall('receiver')):
    app.remove(receiver)
for activity in app.findall('activity'):
    if activity.get(a('name')) == '.SettingsActivity':
        for intent in list(activity.findall('intent-filter')):
            activity.remove(intent)
    if activity.get(a('name')) == '.HomeActivity':
        activity.set(a('label'), label)
        for intent in list(activity.findall('intent-filter')):
            activity.remove(intent)
        intent = ET.SubElement(activity, 'intent-filter')
        ET.SubElement(intent, 'action', {a('name'): 'android.intent.action.MAIN'})
        for category in ['LEANBACK_LAUNCHER', 'LAUNCHER']:
            ET.SubElement(intent, 'category', {a('name'): 'android.intent.category.' + category})
tree.write(manifest, encoding='utf-8', xml_declaration=True)
manifest.write_text('\n'.join(line.rstrip() for line in manifest.read_text().splitlines()) + '\n')
(root / 'config.properties').write_text('''tmdb_key=
watchmode_key=
tmdb_region=RU
latitude=55.1644
longitude=61.4368
city=Челябинск
language=ru
show_crypto=false
currency=RUB
temperature=C
disable_assistant=false
''')
assert m.get('package') == PACKAGE
assert not m.findall('.//category[@' + a('name') + '="android.intent.category.HOME"]')
assert not app.findall('receiver')
assert 'Socket' not in (root / 'app/src/io/freemytv/Adb.java').read_text()
print('Prepared isolated preview: no HOME role, boot receiver or ADB transport')
