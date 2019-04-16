#!/bin/bash

# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

readonly me=$(basename $0)
readonly root=$(realpath $(dirname $0)/..)
readonly build_flags="-c opt"

if [ -z $1 ]; then
  echo >&2
  echo "Usage:" >&2
  echo >&2
  echo "\$ ${me} <flags> path_to_scan {<flags> path_to_scan...}" >&2
  echo >&2
  echo "where flags, which apply to following paths, are:" >&2
  echo " -v or -v+ turns on verbose mode" >&2
  echo " -v-       turns off verbose mode" >&2
  echo " -d or -d+ turns on deep scans into archives e.g. .zip" >&2
  echo " -d-       turns off deep scans" >&2
  echo " --deep    synonym for -d+" >&2
  echo " --nodeep  synonum for -d-" >&2
  echo >&2
  exit 1
fi

readonly build_out=$(
  cd "${root}" >&2
  bazel build ${build_flags} :android_scan >&2
  echo -n $? " "
  echo $(bazel info ${build_flags} bazel-bin)
)
rc=$(echo ${build_out} | cut -d\  -f1)
if [ $rc -ne 0 ]; then
  echo "Could not build android scan binary." >&2
  exit 1
fi

verbose=""
deep=""

readonly bazel_bin=$(echo ${build_out} | cut -d\  -f2-)
while [ -n "$1" ]; do
  case "$1" in
    -*)
      case $(expr "$1" : '--\?\(.*\)') in
        [vV]-) verbose=""; shift; continue;;
        [vV]+) verbose="-v"; shift; continue;;
        [vV]) verbose="-v"; shift; continue;;
        [dD][eE][eE][pP]) deep="--deep"; shift; continue;;
        [nN][oO][dD][eE][eE][pP]) deep=""; shift; continue;;
        [dD]-) deep=""; shift; continue;;
        [dD]+) deep="--deep"; shift; continue;;
        [dD]) deep="--deep"; shift; continue;;
      esac;;
  esac

  target=$(realpath "$1")
  shift
  if [ -d "${target}" ]; then
    find "${target}" -type f -print0 | \
        "${bazel_bin}/android_scan" -0 -f=- ${verbose} ${deep}
  elif [ -f "${target}" ]; then
    "${bazel_bin}/android_scan" ${verbose} ${deep} "${target}"
  else
    echo "Cannot scan ${target} -- neither file nor folder." >&2
    exit 1
  fi
done
