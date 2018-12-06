#!/bin/bash +vx
LIB_PATH=$"./lib/protobuf-java-3.4.0.jar:./lib/slf4j-log4j12-1.7.12.jar:./lib/slf4j-api-1.7.12.jar:./lib/log4j-1.2.17.jar:./lib/javax.annotation.jar"

java -classpath bin/client_classes:$LIB_PATH Coordinator $1 $2 $3
