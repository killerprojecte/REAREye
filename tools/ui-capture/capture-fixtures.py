#!/usr/bin/env python3
"""Capture the implemented fixture routes; no claim of all application states."""
import argparse, json, pathlib, subprocess, sys
p=argparse.ArgumentParser()
p.add_argument('--backend', choices=['miuix','mochi'], required=True)
p.add_argument('--output', type=pathlib.Path, required=True)
a=p.parse_args()
serial=subprocess.check_output(['adb','get-serialno'],text=True).strip()
size=subprocess.check_output(['adb','shell','wm','size'],text=True).strip().split()[-1]
w,h=map(int,size.split('x'))
pages=['cards','card-editor','components','component-editor','wallpapers','more','scene-routes','business-extras','business-extra-editor','custom-bounds']
a.output.mkdir(parents=True,exist_ok=True)
for dark in [False, True]:
 for page in pages:
  folder=a.output/('dark' if dark else 'light')/page
  folder.mkdir(parents=True,exist_ok=True)
  steps=[{'action':'launch','extras':{'page':page,'backend':a.backend,'dark':dark}}, {'action':'wait','seconds':3}, {'action':'screenshot','name':'initial.png'}]
  if not dark:
   steps += [{'action':'record','seconds':6}, {'action':'wait','seconds':1}, {'action':'swipe','points':[w//2,h*3//4,w//2,h//3],'duration_ms':500}, {'action':'wait','seconds':1}, {'action':'screenshot','name':'after-gesture.png'}]
  scenario={'kind':'production-composable-fixture','component':'hk.uwu.reareye/.ui.capture.UiCaptureActivity','steps':steps}
  path=folder/'scenario.json';path.write_text(json.dumps(scenario,indent=2))
  subprocess.run([sys.executable,str(pathlib.Path(__file__).with_name('capture.py')),'--serial',serial,'--scenario',str(path),'--output',str(folder)],check=True)
