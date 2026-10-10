#!/usr/bin/env bash
# Compiles the whole livingpop package (including LivingPopulationManager, which the unit-test runner does not) against the server sources
# and libs, and fails on any error inside livingpop. Other server files may need a newer JDK than 21 (unnamed variables); those errors are ignored.
# Usage: ./tests/compile_livingpop.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 2
CP="$(ls dist/libs/*.jar | grep -v sources | tr '\n' ':')"
OUT="$(mktemp -d)"
errors="$(javac -nowarn -proc:none -Xmaxerrs 2000 -cp "${CP}" -sourcepath java -d "${OUT}" java/org/l2jmobius/gameserver/livingpop/*.java 2>&1 | grep "livingpop.*error")"
rm -rf "${OUT}"
if [ -n "${errors}" ]; then echo "${errors}"; echo "livingpop does NOT compile"; exit 1; fi
echo "livingpop compiles"
