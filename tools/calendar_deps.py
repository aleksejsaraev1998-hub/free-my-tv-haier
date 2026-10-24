#!/usr/bin/env python3
"""Download the small Java-only recurrence runtime, with pinned hashes; never runs on the owner's PC."""
from pathlib import Path
import hashlib
import urllib.request
import shutil
import concurrent.futures

ARTIFACTS = [
    ('lib-recur', '0.17.1', '358a34cdbe565db1c9dabf51049a2b46c4dc082fde968d8ae951d90e001ed231'),
    ('rfc5545-datetime', '0.3', 'e56018f200b51eb900679e2ccb175a698f4a83686c99617ccd84065d415dd3db'),
    ('jems2', '2.23.1', '9dc0a13964e80f1429e0f698542e2717d0795130ccfbbf33edae4a6a3050cbc9'),
]

def ensure(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    def fetch(item):
        name, version, digest = item
        filename = name + '-' + version + '.jar'
        path = directory / filename
        if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            url = 'https://repo.maven.apache.org/maven2/org/dmfs/' + name + '/' + version + '/' + filename
            data = urllib.request.urlopen(url, timeout=45).read(2 * 1024 * 1024)
            if hashlib.sha256(data).hexdigest() != digest:
                raise RuntimeError('Recurrence dependency SHA256 mismatch: ' + name)
            path.write_bytes(data)
        return path
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        return list(pool.map(fetch, ARTIFACTS))

def install(source):
    source=Path(source)
    jars=ensure(source/'app/libs')
    assets=source/'app/assets/calendar-licenses'
    shutil.copytree(Path(__file__).with_name('calendar-licenses'),assets,dirs_exist_ok=True)
    return jars
