#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# SHELLDOC-IGNORE

# -------------------------------------------------------
function showWelcome {
cat <<Welcome-message

This is the standard UIO Developer build environment.
This has all the right tools installed required to build
UIO from source.

Welcome-message
}

# -------------------------------------------------------

function showAbort {
  cat <<Abort-message

ABORTING...

Abort-message
}

# -------------------------------------------------------

function failIfUserIsRoot {
    if [ "$(id -u)" -eq "0" ]; # If you are root then something went wrong.
    then
        cat <<End-of-message

Apparently you are inside this docker container as the user root.
Putting it simply:

   This should not occur.

Known possible causes of this are:
1) Running this script as the root user ( Just don't )
2) Running an old docker version ( upgrade to 1.4.1 or higher )

End-of-message

    showAbort

    logout

    fi
}

# -------------------------------------------------------

function warnIfLowMemory {
    MINIMAL_MEMORY=2046755
    INSTALLED_MEMORY=$(grep -F MemTotal /proc/meminfo | awk '{print $2}')
    if [[ $((INSTALLED_MEMORY)) -lt $((MINIMAL_MEMORY)) ]]; then
        cat <<End-of-message

Your system is running on very little memory.
This means it may work but it wil most likely be slower than needed.

If you are running this via boot2docker you can simply increase
the available memory to at least ${MINIMAL_MEMORY}KiB
(you have ${INSTALLED_MEMORY}KiB )

End-of-message
    fi
}

# -------------------------------------------------------

showWelcome
warnIfLowMemory
failIfUserIsRoot

# -------------------------------------------------------
