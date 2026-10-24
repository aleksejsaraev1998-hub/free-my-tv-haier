#!/usr/bin/env python3
"""Prepare an isolated, non-switching preview from the reviewed upstream revision."""
from pathlib import Path
import subprocess
import sys
import json
import re
import xml.etree.ElementTree as ET

PIN = 'aa34335d25fa2224053d1bb16851e4522a6bbeb0'
PACKAGE = 'io.freemytv.haierpreview'
root = Path(sys.argv[1]).resolve()
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
''')
ns = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ns)
a = lambda name: '{' + ns + '}' + name
manifest = root / 'app/AndroidManifest.xml'
tree = ET.parse(manifest)
m = tree.getroot()
m.set(a('versionName'), '1.1-haier-preview')
app = m.find('application')
app.set(a('label'), 'Free My TV Haier Preview')
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
        activity.set(a('label'), 'Free My TV Haier Preview')
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
