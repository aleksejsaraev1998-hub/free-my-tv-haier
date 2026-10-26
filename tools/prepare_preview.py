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
parser.add_argument('--profile', choices=['standard', 'lite', 'bento', 'bento-home', 'bento-design'], default='standard')
args = parser.parse_args()
lite = args.profile in ('lite', 'bento', 'bento-home', 'bento-design')
home_variant = args.profile == 'bento-home'
PACKAGE = 'io.freemytv.haierpreview.home' if home_variant else ('io.freemytv.haierpreview.bentodesign' if args.profile == 'bento-design' else 'io.freemytv.haierpreview' + ('.' + args.profile if args.profile != 'standard' else ''))
label = 'Free My TV Haier ' + ('Bento HOME Test' if home_variant else 'Bento Preview' if args.profile == 'bento' else 'Lite Preview' if lite else 'Preview')
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
# Browser integration is intentionally independent of system mode switching.
browser_source = Path(__file__).with_name('browser-shortcut.java').read_text()
(root / 'app/src/io/freemytv/BrowserShortcut.java').write_text(
    browser_source.replace('package io.freemytv;', 'package ' + PACKAGE + ';'))
replace_once('HomeApps.java', '    static final int INPUTS = 7;',
             '    static final int INPUTS = 7, BROWSER = 8;')
replace_once('HomeApps.java',
             '        items.add(shortcut(ALL_APPS, "all", Ui.tr("All Apps"), "apps"));\n        return items;',
             '        items.add(shortcut(BROWSER, "browser", Ui.tr("Browser"), "browser"));\n'
             '        items.add(shortcut(ALL_APPS, "all", Ui.tr("All Apps"), "apps"));\n        return items;')
replace_once('HomeApps.java',
             '        items.add(shortcut(SETTINGS, "settings", Ui.tr("Home Settings"), "settings"));',
             '        items.add(shortcut(BROWSER, "browser", Ui.tr("Browser"), "browser"));\n'
             '        items.add(shortcut(SETTINGS, "settings", Ui.tr("Home Settings"), "settings"));')
replace_once('HomeApps.java', '        switch (it.kind) {\n            case APP:',
             '        switch (it.kind) {\n            case BROWSER:\n                return BrowserShortcut.open(a);\n            case APP:')
replace_once('Symbols.java', '        Bitmap b = get(c, name);\n        return b == null ? 0',
             '        if ("browser".equals(name)) return pointSize * 1.15f;\n'
             '        Bitmap b = get(c, name);\n        return b == null ? 0')
replace_once('Symbols.java',
             'static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {',
             'static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {\n'
             '        if ("browser".equals(name)) { BrowserShortcut.draw(canvas, cx, cy, pointSize, color); return; }')
for catalogue in root.glob('app/assets/i18n/*.json'):
    data = json.loads(catalogue.read_text())
    additions = {'Browser': 'Browser', 'Install a browser for Android TV first.': 'Install a browser for Android TV first.'}
    if catalogue.stem == 'ru':
        additions = {'Browser': 'Браузер', 'Install a browser for Android TV first.': 'Сначала установите браузер для Android TV.'}
    for key, value in additions.items():
        data.setdefault(key, value)
    catalogue.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
if args.profile in ('bento', 'bento-home', 'bento-design'):
    from calendar_deps import install
    install(root)
    build = root / 'build.sh'
    code = build.read_text()
    code = code.replace('-classpath "$AJ"', '-classpath "$AJ${CPSEP}app/libs/*"')
    code = code.replace("$(find out/classes -name '*.class')", "$(find out/classes -name '*.class') app/libs/*.jar")
    build.write_text(code)
    for source in Path(__file__).with_name('bento').glob('*.java'):
        (root / 'app/src/io/freemytv' / source.name).write_text(
            source.read_text().replace('package io.freemytv;', 'package ' + PACKAGE + ';'))
    home = root / 'app/src/io/freemytv/HomeActivity.java'
    home.write_text(home.read_text().replace('HomeView', 'BentoView').replace(
        'if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();',
        'if (level >= TRIM_MEMORY_COMPLETE && home != null) home.invalidate();'))
    replace_once('SettingsActivity.java', 'if (screen == MAIN) return false;   // leaves the settings',
                 'if (screen == MAIN) {\n'
                 '                        if (!down && !e.isCanceled()) activity.finish();\n'
                 '                        return true;\n'
                 '                    }')
    translations = json.loads(Path(__file__).with_name('bento').joinpath('translations-ru.json').read_text())
    for catalogue in root.glob('app/assets/i18n/*.json'):
        data = json.loads(catalogue.read_text())
        for key, value in translations.items():
            data.setdefault(key, value if catalogue.stem == 'ru' else key)
        catalogue.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
ns = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ns)
a = lambda name: '{' + ns + '}' + name
manifest = root / 'app/AndroidManifest.xml'
tree = ET.parse(manifest)
m = tree.getroot()
m.set(a('versionCode'), '9')
m.set(a('versionName'), '1.1-haier-' + args.profile + '-preview.8')
# Android 11+ package visibility: ask only for browser intent handlers.
queries = m.find('queries')
intent = ET.SubElement(queries, 'intent')
ET.SubElement(intent, 'action', {a('name'): 'android.intent.action.MAIN'})
ET.SubElement(intent, 'category', {a('name'): 'android.intent.category.APP_BROWSER'})
for scheme in ['http', 'https']:
    intent = ET.SubElement(queries, 'intent')
    ET.SubElement(intent, 'action', {a('name'): 'android.intent.action.VIEW'})
    ET.SubElement(intent, 'category', {a('name'): 'android.intent.category.BROWSABLE'})
    ET.SubElement(intent, 'data', {a('scheme'): scheme})
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
        if home_variant:
            home_intent = ET.SubElement(activity, 'intent-filter')
            ET.SubElement(home_intent, 'action', {a('name'): 'android.intent.action.MAIN'})
            for category in ['HOME', 'DEFAULT']:
                ET.SubElement(home_intent, 'category', {a('name'): 'android.intent.category.' + category})
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
assert home_variant == bool(m.findall('.//category[@' + a('name') + '="android.intent.category.HOME"]'))
assert not app.findall('receiver')
assert 'Socket' not in (root / 'app/src/io/freemytv/Adb.java').read_text()
print('Prepared safe HOME test variant' if home_variant else 'Prepared isolated preview: no HOME role, boot receiver or ADB transport')
