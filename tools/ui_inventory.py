#!/usr/bin/env python3
"""Reproducible source inventory, not screenshots or a substitute for UI execution."""
import json,re,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
files=[]
for path in sorted((root/'app/src/main/java').rglob('*.kt')):
    source=path.read_text()
    imports=re.findall(r'^import (top\.yukonga\.miuix\S+)',source,re.M)
    screens=re.findall(r'fun\s+(\w*(?:Screen|Dialog|BottomSheet))\s*\(',source)
    if imports or screens:
        files.append({'path':path.relative_to(root).as_posix(),'vendorImports':imports,'screenEntryPoints':screens})
print(json.dumps({'kind':'source-inventory','baseline':'e554a382dabdb79994fa11a4482c072c9f968c82','screenshotEvidence':False,'files':files},ensure_ascii=False,indent=2))
