#!/usr/bin/env bash
# Regenerates gradle/verification-metadata.xml from an empty Gradle home, so parent POMs and BOMs are recorded too.
# Fails when a version that was already listed gets a different checksum: only new versions may be added.
set -euo pipefail

cd "$(dirname "$0")/.."
file=gradle/verification-metadata.xml
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp "$file" "$work/previous.xml"
rm "$file"

./gradlew -g "$work/gradle-home" --no-daemon -q --write-verification-metadata sha256 \
    spotlessCheck compileKotlin compileTestKotlin shadowJar validatePlugins jacocoTestKitAgent \
    generatePomFileForPluginMavenPublication generateMetadataFileForPluginMavenPublication \
    test --tests '*KotlinStdlibFloorTest*' jacocoTestReport jacocoTestKitReport sonarResolver help > "$work/gradle.log" 2>&1 \
    || { cat "$work/gradle.log"; cp "$work/previous.xml" "$file"; exit 1; }

if ! python3 - "$work/previous.xml" "$file" <<'PY'
import sys
import xml.etree.ElementTree as ET

NS = {"v": "https://schema.gradle.org/dependency-verification"}

def checksums(path):
    result = {}
    for component in ET.parse(path).getroot().iterfind("v:components/v:component", NS):
        coordinates = (component.get("group"), component.get("name"), component.get("version"))
        for artifact in component.iterfind("v:artifact", NS):
            for sha in artifact.iterfind("v:sha256", NS):
                result[coordinates + (artifact.get("name"),)] = sha.get("value")
    return result

previous, current = checksums(sys.argv[1]), checksums(sys.argv[2])
changed = sorted(key for key in previous.keys() & current.keys() if previous[key] != current[key])
if changed:
    print("These artifacts were already verified but are now served with a different checksum:")
    for group, name, version, artifact in changed:
        print(f"  {group}:{name}:{version} {artifact}")
    print("That is what dependency verification protects against. Nothing was accepted; check the artifacts by hand.")
    sys.exit(1)
added = sorted({key[:3] for key in current.keys() - previous.keys()})
removed = sorted({key[:3] for key in previous.keys() - current.keys()})
print(f"Verification metadata: {len(added)} component version(s) added, {len(removed)} no longer used.")
for group, name, version in added:
    print(f"  + {group}:{name}:{version}")
PY
then
    cp "$work/previous.xml" "$file"
    exit 1
fi
