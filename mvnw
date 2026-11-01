#!/usr/bin/env bash
set -e
export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
export PATH="$JAVA_HOME/bin:$PATH"
MAVEN_BIN="/Users/abhinavv/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn"
exec "$MAVEN_BIN" "$@"
