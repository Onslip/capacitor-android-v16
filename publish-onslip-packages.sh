#!/bin/bash

set -ex
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

git clean -d -x -i
pod repo update
bash scripts/prerelease.sh
(cd android && npm publish)
(cd cli && npm install && npm publish)
