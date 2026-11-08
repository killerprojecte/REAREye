#!/usr/bin/env python3
"""Read public package metadata using job credentials without logging credentials."""
import base64, json, os, pathlib, urllib.error, urllib.request, xml.etree.ElementTree as ET
base='https://maven.pkg.github.com/compose-miuix-ui/miuix/top/yukonga/miuix/kmp/'
actor=os.environ['GITHUB_ACTOR']
report={'attempts':[], 'artifacts':{}}
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward authorization to another destination.
opener=urllib.request.build_opener(NoRedirect)
selected=None
for source, env in [('github-token','GITHUB_TOKEN'),('provided-package-token','GITHUB_PKG_TOKEN')]:
    token=os.environ.get(env)
    if not token:
        report['attempts'].append({'source':source,'status':'not-configured'})
        continue
    authorization='Basic '+base64.b64encode((actor+':'+token).encode()).decode()
    req=urllib.request.Request(base+'miuix-ui/maven-metadata.xml',headers={'Authorization':authorization})
    try:
        with opener.open(req,timeout=30) as response:
            payload=response.read()
        ET.fromstring(payload)
        report['attempts'].append({'source':source,'status':200})
        selected=authorization
        report['credentialSource']=source
        break
    except urllib.error.HTTPError as e:
        report['attempts'].append({'source':source,'status':e.code})
    except (urllib.error.URLError, ET.ParseError):
        report['attempts'].append({'source':source,'status':'network-or-invalid-metadata'})
if selected:
    for name in ['miuix-ui','miuix-preference','miuix-icons','miuix-blur','miuix-shader','miuix-glass']:
        try:
            req=urllib.request.Request(base+name+'/maven-metadata.xml',headers={'Authorization':selected})
            with opener.open(req,timeout=30) as response:
                root=ET.fromstring(response.read())
            report['artifacts'][name]={'latest':root.findtext('versioning/latest'), 'release':root.findtext('versioning/release'), 'versions':[v.text for v in root.findall('versioning/versions/version')], 'lastUpdated':root.findtext('versioning/lastUpdated')}
        except urllib.error.HTTPError as e:
            report['artifacts'][name]={'status':e.code}
        except (urllib.error.URLError, ET.ParseError):
            report['artifacts'][name]={'status':'network-or-invalid-metadata'}
pathlib.Path('package-evidence').mkdir(exist_ok=True)
pathlib.Path('package-evidence/metadata.json').write_text(json.dumps(report,indent=2))
# Only non-secret result codes and public Maven metadata are printed.
print(json.dumps(report,indent=2))
if not selected:
    raise SystemExit(1)
