# Implementation of Key-Value Store with Configurable Consistency
-----------------------------------------------------------------------
## Introduction
This is a implemention of a Cassandra key-value pair store distributed application. The distributed system has multiple replicas. Every replica knows about all other replicas and communication between them are handled using TCP protocols. The distributed key-value store supports read from and write to keyValuePair database. This project uses Google’s Protocol Buffer for marshalling and unmarshalling messages and uses TCP sockets for sending and receiving these messages.

Each replica server is be a key-value store. Keys are unsigned integers between 0 and 255. Values are strings.
Each replica server supports the following key-value operations:
• get key – given a key, return its corresponding value 
• put key value – if the key does not already exist, creates a new key-value pair; otherwise, update the key to the new value

For simplicity, each replica only needs to store key-value pairs in its memory. That is, there is no need to flush the memory content to persistent storage.
As with Cassandra, to handle a write request, the replica must first log this write in a write-ahead log on persistent storage before updating its in-memory data structure. In this way, if a replica failed and restarted, it can restore its memory state by replaying the disk log.

-----------------------------------------------------------------------
## Overview:
### Cordinator/replicas:

The majority of the functionality is provided by the coordinator.java. It handles all the read/write requests from client and read/write requests from coordinator to replicas and return requests from the replicas to the coordinator.

The coordinator takes three command line inputs: a local file that stores the names, IP addresses, and port numbers of all replicas, port name of current replica, and runmode.

    $> ./coordinator.sh Nodes.txt 9090 1
    
The file (Nodes.txt) should contain a list of names, IP addresses, and ports, in the format “<name> <public-ip-address> <port>”, of all of the replicas.

#### For example, if four replicas with names: “S1”, “S2”, “S3”, and “S4” are running on server 128.226.114.201 on port 9090, 9091, 9092, and 9093, then branches.txt should contain:
    S1 128.226.114.201 9090
    S2 128.226.114.201 9091
    S3 128.226.114.201 9092
    S4 128.226.114.201 9093

### client:
The  client script takes Nodes.txt file as input to start. 

    $> ./client.sh Nodes.txt

Once started, the client acts as a console, allowing users to issue a stream of requests. The client selects one replica server as the coordinator for all its requests. That is, all requests from a single client are handled by the same coordinator. It is able to launch multiple clients, potentially issue requests to different coordinators at the same time.


## Implementation details:

When a client sends a read request to the coordinator, the coordinator send proto message to all the replicas associated with that key. Then replicas look through their keyValuePair database and respond accordingly. The coordinator collects the responses and as soon as it satiesfies the require consistency it send a response to the client with most up to date value associated with that key. The coordinator also holds a list of replicas that require readRepair.

When a write request is received at the coordinator, once again it identifies the replicas and send them write request. The replicas first store that keyvalue pair in a write-ahead-log file and then update the database accordingly. It also checks if the key exits in the database. If it does then it verifies with the timestamp and updates it accordingly.

The code has been written to handle the hintedhandoff as well.

## Programming language of implementation : JAVA

## To generate cassandra.java using protoc :
    $> bash
    $> export PATH=/home/vchaska1/protobuf/bin:$PATH

### cd to Consistent-Key-Value-Store the run command belows :

    $> protoc --java_out=./src/ ./src/cassandra.proto


## Steps to Compile and Run the program

### 1. Compile the code using Makefile which is already present in the repo.

    $> make

### 2. Run the multiple coordinator/replica servers by providing below command line arguments :
    
    $>./coordinator.sh <Nodes.txt> <port> <readRepairOrHintedHandOff>

    readRepairOrHintedHandOff = 1 => readRepair Mode
    readRepairOrHintedHandOff = 2 => hintedHandOff Mode
### 3. Run the client as :
    
    $> ./client.sh <Nodes.txt>
