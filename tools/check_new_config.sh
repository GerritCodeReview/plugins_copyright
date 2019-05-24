#!/bin/sh

# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

readonly me=$(basename $0)
readonly root=$(realpath $(dirname $0)/..)
readonly build_flags="-c opt"

function usage() {
  echo
  echo "Usage:"
  echo
  echo "\$ ${me} <plugin-name> <project.config>"
  echo
  echo "where:"
  echo " <plugin-name> is the name of the copyright scanner plugin"
  echo " <project.config> is the full pathname of the project.config file you want to push"
  echo
  if test $# -eq 2; then
    if $(basename "${2}") = "project.config"; then
      echo "${2} is not a regular file."
      echo
    else
      echo "The project.config full path must end with project.config -- not $(basename $2)"
      echo
    fi
  fi
}

if test $# -ne 2; then
  usage >&2
  exit 1
fi

if test $(basename "${2}") != "project.config" -o ! -f "${2}"; then
  usage >&2
  exit 1
fi

readonly build_out=$(
  cd "${root}" >&2
  bazel build ${build_flags} :check_new_config >&2 2>/dev/null
  echo -n $? " "
  echo $(bazel info ${build_flags} bazel-bin 2>/dev/null)
)
rc=$(echo ${build_out} | cut -d\  -f1)
if test $rc -ne 0; then
  (
    cd "${root}" >&2
    bazel build ${build_flags} :check_new_config >&2
  )
  echo >&2
  echo "Could not build check_new_config binary." >&2
  echo >&2
  exit 1
fi

readonly bazel_bin=$(echo ${build_out} | cut -d\  -f2-)

"${bazel_bin}/check_new_config" "${1}" "${2}"
rc=$?
if test ${rc} -ne 0; then
  echo >&2
  echo "Please correct the errors above and run this tool again." >&2
fi
exit ${rc}
