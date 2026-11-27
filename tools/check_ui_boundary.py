#!/usr/bin/env python3
"""Fail if application Kotlin code reaches the MIUIX implementation directly."""
import argparse,json,pathlib,re,sys
p=argparse.ArgumentParser();p.add_argument('--json',action='store_true');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1]
findings=[]
# Preserve line numbers while removing comments and string literals, including raw strings.
trivia=re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|/\*[\s\S]*?\*/|//[^\n]*')
for path in sorted(root.rglob('*.kt')):
 rel=path.relative_to(root)
 if any(x in {'.git','.gradle','build','.kotlin'} for x in rel.parts) or rel.parts[0]=='mixui':continue
 source=trivia.sub(lambda m:'\n'*m.group().count('\n'),path.read_text())
 for line,text in enumerate(source.splitlines(),1):
  for match in re.finditer(r'\btop\.yukonga\.miuix\.[A-Za-z0-9_.*.]+',text):
   findings.append({'path':str(rel),'line':line,'reference':match.group(),'kind':'import' if text.lstrip().startswith('import ') else 'qualified-reference'})
if a.json:print(json.dumps({'violations':findings,'passed':not findings},indent=2))
else:
 for f in findings:print(f'{f["path"]}:{f["line"]}: {f["reference"]}')
 print(f'{len(findings)} direct MIUIX references outside mixui')
sys.exit(bool(findings))
