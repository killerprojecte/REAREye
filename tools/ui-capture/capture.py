#!/usr/bin/env python3
"""Record an explicitly described route on an already provisioned test device.
Does not seed app data, bypass gates, or label unvisited routes as captured.
"""
import argparse, hashlib, json, pathlib, subprocess, time, uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--scenario', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    scenario = json.loads(args.scenario.read_text())
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb, '-s', args.serial]
    def adb(*command, **kwargs):
        return subprocess.run(prefix + list(command), check=True, timeout=60, **kwargs)
    adb('get-state', capture_output=True)
    evidence = {'scenario': scenario, 'serial': args.serial, 'captures': [],
                'note': 'Route identity requires review of the images; successful capture alone does not prove coverage.'}
    recorder = None
    remote = '/sdcard/reareye-ui-' + uuid.uuid4().hex + '.mp4'
    try:
        for step in scenario['steps']:
            action = step['action']
            if action == 'launch':
                adb('shell', 'am', 'start', '-W', '-n', scenario['component'])
            elif action == 'tap':
                adb('shell', 'input', 'tap', str(step['x']), str(step['y']))
            elif action == 'swipe':
                adb('shell', 'input', 'swipe', *map(str, step['points']), str(step.get('duration_ms', 350)))
            elif action == 'back':
                adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
            elif action == 'wait':
                time.sleep(step['seconds'])
            elif action == 'screenshot':
                name = step['name']
                if pathlib.Path(name).name != name or not name.endswith('.png'):
                    raise ValueError('Screenshot name must be a PNG filename')
                data = adb('exec-out', 'screencap', '-p', capture_output=True).stdout
                if not data.startswith(b'\x89PNG\r\n\x1a\n'):
                    raise ValueError('Device did not return a PNG')
                (out / name).write_bytes(data)
                evidence['captures'].append({'file': name, 'sha256': hashlib.sha256(data).hexdigest()})
            elif action == 'record':
                if recorder is not None:
                    raise ValueError('Only one recording per scenario')
                recorder = subprocess.Popen(prefix + ['shell', 'screenrecord', '--time-limit', str(step['seconds']), remote])
            else:
                raise ValueError('Unknown scenario action: ' + action)
        if recorder is not None:
            if recorder.wait(timeout=190) != 0:
                raise RuntimeError('screenrecord failed')
            adb('pull', remote, str(out / 'animation.mp4'))
            evidence['captures'].append({'file': 'animation.mp4', 'sha256': hashlib.sha256((out / 'animation.mp4').read_bytes()).hexdigest()})
        evidence['captureCompleted'] = True
    finally:
        if recorder is not None and recorder.poll() is None:
            recorder.terminate()
            recorder.wait(timeout=10)
        (out / 'evidence.json').write_text(json.dumps(evidence, indent=2))

if __name__ == '__main__':
    main()
