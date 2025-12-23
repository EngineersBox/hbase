#!/usr/bin/env bash

mvn -DskipTests -Dhadoop.profile=3.0 clean install
