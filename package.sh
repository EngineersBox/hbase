#!/usr/bin/env bash

./clean_install.sh
mvn -DskipTests -Dhadoop.profile=3.0 package assembly:single
