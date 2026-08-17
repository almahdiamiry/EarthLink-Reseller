#!/bin/bash
echo "## Concurrency grep summary:"
grep -rnE "(synchronized|Mutex|withTransaction)" app/src/main/java/ | awk -F: '{print $1 ":" $2 " " $3}'
