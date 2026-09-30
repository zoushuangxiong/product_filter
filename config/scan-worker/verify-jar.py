#!/usr/bin/env python3
"""After mvn package, verify the real worker JAR against a local in-process cloud broker."""
from pathlib import Path
import os, subprocess, tempfile, zipfile
root = Path(__file__).resolve().parents[2]
jar = root / 'ruoyi-admin/target/ruoyi-admin.jar'
with tempfile.TemporaryDirectory(prefix='scan-worker-libs-') as directory:
    libs = Path(directory)
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libs / Path(name).name).write_bytes(archive.read(name))
    classpath = os.pathsep.join([str(root / 'ruoyi-system/target/test-classes'), str(root / 'ruoyi-system/target/classes'), str(libs / '*')])
    subprocess.run(['java', '-cp', classpath, 'com.ruoyi.system.service.product.ScanWorkerJarIT', str(jar)], check=True)
