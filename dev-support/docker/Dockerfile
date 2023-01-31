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

# Dockerfile for installing the necessary dependencies for building Ozone.

FROM ubuntu:jammy

WORKDIR /root

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

#####
# Disable suggests/recommends
#####
RUN echo APT::Install-Recommends "0"\; > /etc/apt/apt.conf.d/10disableextras
RUN echo APT::Install-Suggests "0"\; >>  /etc/apt/apt.conf.d/10disableextras

ENV DEBIAN_FRONTEND noninteractive
ENV DEBCONF_TERSE true

######
# Install packages from apt
######
RUN apt-get -q update \
    && apt-get -q install -y --no-install-recommends python3 \
    && apt-get -q install -y --no-install-recommends \
      apt-utils build-essential bzip2 libbz2-dev clang cmake curl libcurl4-openssl-dev \
      fuse libfuse-dev gcc g++ git \
      libssl-dev libprotobuf-dev libprotoc-dev libsasl2-dev zlib1g-dev \
      locales \
      make maven \
      openjdk-8-jdk leiningen \
      sudo \
    && update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN locale-gen en_US.UTF-8
ENV LANG='en_US.UTF-8' LANGUAGE='en_US:en' LC_ALL='en_US.UTF-8'
ENV PYTHONIOENCODING=utf-8

######
# Set env vars required to build UIO
######
ENV MAVEN_HOME /usr
# JAVA_HOME must be set in Maven >= 3.5.0 (MNG-6003)
ENV JAVA_HOME /usr/lib/jvm/java-8-openjdk-amd64

###
# Avoid out of memory errors in builds
###
ENV MAVEN_OPTS -Xms256m -Xmx3072m

####
# Install packages
####
# RUN pkg-resolver/install-common-pkgs.sh
# RUN pkg-resolver/install-spotbugs.sh ubuntu:focal
# RUN pkg-resolver/install-boost.sh ubuntu:focal
# RUN pkg-resolver/install-protobuf.sh ubuntu:focal
# RUN pkg-resolver/install-hadolint.sh ubuntu:focal
# RUN pkg-resolver/install-intel-isa-l.sh ubuntu:focal

# Add a welcome message and environment checks.
COPY uio_env_checks.sh /root/uio_env_checks.sh
RUN chmod 755 /root/uio_env_checks.sh
RUN echo '${HOME}/uio_env_checks.sh' >> /root/.bashrc
