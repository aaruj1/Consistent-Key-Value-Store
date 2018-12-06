LIB_PATH=./lib/protobuf-java-3.4.0.jar:./lib/slf4j-log4j12-1.7.12.jar:./lib/slf4j-api-1.7.12.jar:./lib/log4j-1.2.17.jar:./lib/javax.annotation.jar
all: clean
	mkdir bin
	mkdir bin/client_classes
	javac -classpath $(LIB_PATH) -d bin/client_classes/ src/Client.java src/Cassandra.java src/Coordinator.java


clean:
	rm -rf bin/

