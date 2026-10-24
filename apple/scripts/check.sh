#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .build/checks
swiftc -swift-version 5 -parse-as-library -emit-module -emit-library -module-name HerCore \
  Sources/HerCore/*.swift -o .build/checks/libHerCore.dylib \
  -emit-module-path .build/checks/HerCore.swiftmodule
swiftc -swift-version 5 -parse-as-library -I .build/checks -L .build/checks -lHerCore \
  Tests/HerCoreTests/CoreChecks.swift scripts/RunChecks.swift \
  -o .build/checks/HerChecks -Xlinker -rpath -Xlinker @executable_path
.build/checks/HerChecks
