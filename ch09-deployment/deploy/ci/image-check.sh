#!/usr/bin/env bash
# Design 006 T2 (D1, D6, D9; §3.1.1 B1-B3, B5): what the release image holds.
#   deploy/ci/image-check.sh trailhead/order-integration:006
# Reads the image's configuration (user, entrypoint, working directory, environment), then runs the image twice, as
# uid 10001, with no network and no host folder mounted (--rm: each container is removed when it ends): once for the
# Java version and the files under /app, once to stream /app out as a tar (read on the host under target/, where the
# jar entries are listed with unzip). It also checks that every FROM line of src/main/docker/Dockerfile names the
# pinned base; the image's own JAVA_VERSION ties the image to that base.
# Each failed property prints `FAILED <property>: expected <x>, got <y>`; the script exits 1 after all checks.
# Exit 0: every property holds. Exit 2: wrong arguments, or the image does not exist.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
[ $# -eq 1 ] || { echo "usage: image-check.sh <image>" >&2; exit 2; }
image=$1

base='eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c'
want_user='10001:10001'
want_entrypoint='["java","-XX:MaxRAMPercentage=50","-XX:+ExitOnOutOfMemoryError","-jar","/app/application.jar"]'
want_workdir='/app'
want_java='25.0.5'
want_java_env='jdk-25.0.5+8'
# The base image's own variables; anything else is a setting baked into the image (D9: settings come from the pod)
base_env='PATH JAVA_HOME LANG LANGUAGE LC_ALL JAVA_VERSION'
dockerfile="$root/src/main/docker/Dockerfile"

failures=0
fail() { echo "FAILED $1: expected $2, got $3"; failures=$((failures + 1)); }
check() { if [ "$3" = "$2" ]; then echo "ok $1: $3"; else fail "$1" "$2" "$3"; fi; }
# A test, fault or spec-test name: "Test" (class names), "test" as a word start (jar and file names: spring-boot-test,
# testcontainers; not "latest"), FaultHook, spec-test, or "fault" once "default" is taken out (application-default.*)
suspicious() {
  awk -F/ '{ n = $NF; l = tolower(n); d = l; gsub(/default/, "", d) }
    n ~ /Test|FaultHook/ || l ~ /(^|[^a-z])test|spec-test/ || d ~ /fault/ { print }'
}

docker image inspect "$image" > /dev/null 2>&1 || { echo "image-check.sh: image $image not found" >&2; exit 2; }

# 1. The image configuration.
check user "$want_user" "$(docker image inspect "$image" --format '{{.Config.User}}')"
check entrypoint "$want_entrypoint" "$(docker image inspect "$image" --format '{{json .Config.Entrypoint}}')"
check workdir "$want_workdir" "$(docker image inspect "$image" --format '{{.Config.WorkingDir}}')"
env_lines=$(docker image inspect "$image" --format '{{range .Config.Env}}{{println .}}{{end}}')
extra_env=$(echo "$env_lines" | sed -n 's/=.*//p' | grep -v -x -F -f <(tr ' ' '\n' <<< "$base_env") | tr '\n' ' ' || true)
[ -z "$extra_env" ] && echo "ok no-setting-in-image: only the base's variables ($base_env)" \
  || fail no-setting-in-image "only the base's variables ($base_env)" "also ${extra_env% }"
check base-java-version "$want_java_env" "$(echo "$env_lines" | sed -n 's/^JAVA_VERSION=//p')"

# 2. Inside the image, as the release's user. Each fact is one `key=value` line; lists are joined by spaces.
facts=$(docker run --rm --network none --user "$want_user" --entrypoint sh "$image" -c '
  v=$(java -version 2>&1 | head -1 | sed -n "s/.*version \"\([^\"]*\)\".*/\1/p")
  echo "java=${v:-none}"
  if [ -d /app ]; then
    echo "app=present"
    echo "jar=$( [ -f /app/application.jar ] && [ -d /app/lib ] && echo present || echo missing)"
    echo "writable=$(find /app -writable 2>/dev/null | head -5 | tr "\n" " ")"
    echo "owner=$(find /app ! -user 0 -o ! -group 0 2>/dev/null | head -5 | tr "\n" " ")"
    echo "config=$(find /app -type d -name config 2>/dev/null | head -5 | tr "\n" " ")"
  else
    echo "app=missing"
  fi
' 2>&1) || { fail run "the image to start sh as $want_user" "$(echo "$facts" | head -1)"; facts=""; }
fact() { echo "$facts" | sed -n "s/^$1=//p" | head -1 | sed 's/ *$//'; }

check java-version "$want_java" "$(fact java)"
if [ "$(fact app)" = present ]; then
  check application-jar "/app/application.jar and /app/lib present" \
    "$( [ "$(fact jar)" = present ] && echo "/app/application.jar and /app/lib present" || echo "missing")"
  w=$(fact writable); [ -z "$w" ] && echo "ok app-read-only: nothing under /app writable by $want_user" \
    || fail app-read-only "nothing under /app writable by $want_user" "$w"
  o=$(fact owner); [ -z "$o" ] && echo "ok app-owner: everything under /app owned by root:root" \
    || fail app-owner "everything under /app owned by root:root" "$o"
  c=$(fact config); [ -z "$c" ] && echo "ok no-config: no config/ under /app" || fail no-config "no config/ under /app" "$c"

  # 3. The files and the application jar's own entries. Library jars are judged by their name only: their classes
  #    are third-party (Camel's FaultToleranceConfigurationDefinition is not a fault hook).
  work="$root/target/image-check"
  clean() { [ ! -d "$work" ] || { chmod -R u+w "$work"; rm -rf "$work"; }; }   # the copy keeps /app's a-w modes
  clean; mkdir -p "$work/app"
  docker run --rm --network none --user "$want_user" --entrypoint tar "$image" -cf - -C /app . | tar -xf - -C "$work/app"
  files=$(cd "$work/app" && find . -type f | sed 's|^\./||' | sort)
  t=$(echo "$files" | suspicious | head -5 | tr '\n' ' ')
  [ -z "$t" ] && echo "ok no-test-files: no test, fault, FaultHook or spec-test file under /app" \
    || fail no-test-files "no test, fault, FaultHook or spec-test file under /app" "${t% }"
  if [ -f "$work/app/application.jar" ]; then
    entries=$(unzip -Z1 "$work/app/application.jar" | sed 's|^BOOT-INF/classes/||' \
      | grep -v -E '^(META-INF/|BOOT-INF/|org/springframework/boot/loader/)' || true)
    t=$(echo "$entries" | suspicious | head -5 | tr '\n' ' ')
    [ -z "$t" ] && echo "ok no-test-classes: no test, fault, FaultHook or spec-test entry in application.jar" \
      || fail no-test-classes "no test, fault, FaultHook or spec-test entry in application.jar" "${t% }"
    c=$(echo "$entries" | grep -E '^config/' | head -5 | tr '\n' ' ' || true)
    [ -z "$c" ] && echo "ok no-config-in-jar: no config/ entry in application.jar" \
      || fail no-config-in-jar "no config/ entry in application.jar (Spring reads classpath:/config/)" "${c% }"
  fi
  clean
else
  fail app "the application under /app" "no /app in the image"
fi

# 4. The Dockerfile's base, by the pinned digest (every stage).
froms=$(grep -E '^[[:space:]]*FROM[[:space:]]' "$dockerfile" | awk '{print $2}' || true)
if [ -z "$froms" ]; then
  fail dockerfile-base-digest "FROM $base" "no FROM line in $dockerfile"
else
  while read -r f; do check dockerfile-base-digest "$base" "$f"; done <<< "$froms"
fi

if [ "$failures" -gt 0 ]; then
  echo "image-check.sh $image: $failures check(s) failed"
  exit 1
fi
echo "image-check.sh $image: all checks passed"
