#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="${REAREYE_BUILD_DIR:-$root/.local-build}"
mkdir -p "$work/java-home/.android" "$work/gradle-home"
export ANDROID_USER_HOME="$work/java-home/.android"
export ANDROID_SDK_HOME="$work/java-home"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$work/gradle-home}"
# Execution environments can issue a different proxy port for each invocation.
# Derive it now rather than preserving a stale address from an earlier command.
if [[ -n "${HTTPS_PROXY:-}" ]]; then
    proxy_args="$(python3 - <<'PY'
import os, urllib.parse
p=urllib.parse.urlparse(os.environ['HTTPS_PROXY'])
port=p.port or (443 if p.scheme=='https' else 80)
print(f'-Dhttp.proxyHost={p.hostname} -Dhttp.proxyPort={port} -Dhttps.proxyHost={p.hostname} -Dhttps.proxyPort={port}')
PY
)"
    export GRADLE_OPTS="${GRADLE_OPTS:-} $proxy_args"
fi
cd "$root"
args=(--no-daemon --max-workers="${GRADLE_WORKERS:-1}" "-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Duser.home=$work/java-home" -Pkotlin.compiler.execution.strategy=in-process)
if [[ -n "${GRADLE_BIN:-}" ]]; then
    exec "$GRADLE_BIN" "${args[@]}" "$@"
else
    exec bash ./gradlew "${args[@]}" "$@"
fi
