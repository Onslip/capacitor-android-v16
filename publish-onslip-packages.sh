#!/bin/bash

set -ex

git clean -d -x -i
pod repo update
bash scripts/prerelease.sh
(cd android && npm publish)
(cd cli && npm install && npm publish)
