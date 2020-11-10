#!/usr/bin/env bash
# Copyright 2018 Telstra Open Source
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#

set -eu ${DEBUG:+-x}

PATH=${PATH}:/opt/storm/bin

cd /app

case ${WFM_TOPOLOGIES_MODE:-} in

  blue)
    make kill-all-blue
    exec make deploy-all-blue
    ;;

  green)
    make kill-all-green
    exec make deploy-all-green
    ;;

  *)
    make kill-all
    exec make deploy-all
    ;;
esac
