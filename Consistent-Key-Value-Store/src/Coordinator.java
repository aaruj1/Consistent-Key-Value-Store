import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * @author aaruj1
 *
 */
public class Coordinator {
	private static String currentNodeName;

	private static String currentIPAddress;
	private static int currentPort;
	private static int replicaFactor = 3; // 3
	private static int numberOfReplica = 4;// 4;

	static boolean isHintedHandoff = false;
	static boolean isreadRepair = false;
	static boolean isreadRepairNeeded = false;
	static int hintedHandoffOrReadRepair;

	static int consistencyLevel = 0;
	static Socket clientReadSocket;
	static Socket clientWriteSocket;

	private static Map<String, Cassandra.NodeId.Builder> nodesMap = new TreeMap<>();
	private static Map<Integer, Cassandra.KeyValuePair.Builder> currentKeyValuePairMap = new ConcurrentSkipListMap<>();
	private static TreeMap<String, Cassandra.ResponseToCoordinator.Builder> responseFromReplicasMap;
	private static TreeMap<String, Cassandra.DownReplica.Builder> downReplicasMap = new TreeMap<>();

	private static String readErrorMessage = "Unable to reach during read operation to node ";
	private static String writeErrorMessage = "Unable to reach during write operation to node ";
	private static String consistencyErrorMessage = "Unable to communicate required number of replicas for key ";

	// HintedHandOff
	private static TreeMap<String, List<Cassandra.CoordinatorMessage.Builder>> hintedHandOffMap = new TreeMap<>();

	private static Integer noOfReplicaResponseReceived = 0;
//	private static Map<Integer, Cassandra.KeyValuePair.Builder> WAL_KeyValuePairMap = new ConcurrentSkipListMap<>();

	public static final String WAL_DIRECTORY_NAME = "WAL";

	public static final String HHF_DIRECTORY_NAME = "HHFL";

	public static void main(String[] args) {

		ServerSocket serverSocket = null;
		Socket socket = null;

		try {
			if (args.length != 3) {
				System.err.println("Please provide : <nodes-filename> <port> <ReadRepair : 1> OR <HintedHandoff : 2>");
				System.exit(0);
			}

			String nodeFileName = args[0];
			currentPort = Integer.parseInt(args[1]);
			hintedHandoffOrReadRepair = Integer.parseInt(args[2]);
			currentIPAddress = InetAddress.getLocalHost().getHostAddress();
			System.out.println("CurrentIPAddress : " + currentIPAddress + "  currentPort : " + currentPort);

			File file = new File(nodeFileName);
			if (!file.exists()) {
				System.err.println("File does not exist.");
				System.exit(0);
			}
			BufferedReader bufferedReader = new BufferedReader(new FileReader(file));

			String line = "";
			while ((line = bufferedReader.readLine()) != null) {
				String nodeName = line.split(" ")[0].trim();
				String nodeIpAddress = line.split(" ")[1].trim();
				int nodePort = Integer.parseInt(line.split(" ")[2].trim());
				if (nodePort == currentPort) {
					currentNodeName = nodeName;
				}

				System.out.println("NodeName : " + nodeName + "  nodeIPAddress : " + nodeIpAddress + " nodePort : " + nodePort);

				Cassandra.NodeId.Builder nodeObj = Cassandra.NodeId.newBuilder();
				nodeObj.setName(nodeName);
				nodeObj.setIp(nodeIpAddress);
				nodeObj.setPort(nodePort);
				nodesMap.put(nodeObj.getName(), nodeObj);
			}

			if (hintedHandoffOrReadRepair == 1) {
				isreadRepair = true;
			} else if (hintedHandoffOrReadRepair == 2) {
				isHintedHandoff = true;
				readHintedHandOffLog();
				System.out.println("Populated List from HHF_MAP :: \n" + hintedHandOffMap.toString());
			}

			readFromWAL();
			System.out.println("Populated Table from WAL :: \n" + currentKeyValuePairMap.toString());

			serverSocket = new ServerSocket(currentPort);

			if (bufferedReader != null) {
				bufferedReader.close();
			}

			System.out.println("");

			while (true) {

				socket = serverSocket.accept();
				System.out.println("Server Listening..............");
				InputStream inputStream = socket.getInputStream();
				Cassandra.CoordinatorMessage coordinatorMessage = Cassandra.CoordinatorMessage.parseDelimitedFrom(inputStream);

				if (coordinatorMessage.hasReadRequestFromClient()) {
					System.out.println(" ============== Read Request From Client to Coordinator  ============== ");

					clientReadSocket = socket;

					noOfReplicaResponseReceived = 0;
					responseFromReplicasMap = new TreeMap<>();
					int key = coordinatorMessage.getReadRequestFromClient().getKey();
					String consistencyLevelType = coordinatorMessage.getReadRequestFromClient().getConsistencyLevel().toString();

					if (consistencyLevelType.equalsIgnoreCase("ONE")) {
						consistencyLevel = 1;
					} else if (consistencyLevelType.equalsIgnoreCase("QUORUM")) {
						consistencyLevel = (replicaFactor / 2) + 1; // In case of QUORUM, it has to be majority
					}

					System.out.println("Key : " + key + " consistencyLevel : " + consistencyLevelType);

					int primaryReplicaIndex = (key * nodesMap.size()) / 256;

//					100 *2 /256 = 0
					System.out.println("Primary Replica Index : " + primaryReplicaIndex);

					String[] nodesKeys = nodesMap.keySet().toArray(new String[nodesMap.size()]);

					long timeStampInMillis = Instant.now().toEpochMilli();
//					long timeTesting = System.currentTimeMillis();
//					System.out.println("System.currentTimeMillis() : " + timeTesting);

					int messageCounter = 0;

//					List<Cassandra.CoordinatorMessage.Builder> hintedHandOffList = new ArrayList<>();

					for (int i = 0; i < replicaFactor; i++) {

						int replicaIndex = (primaryReplicaIndex + nodesMap.size() + i) % nodesMap.size();
//						(0 +2 + 0 )%2
//						(0 +2 + 1 )%2

						Cassandra.NodeId.Builder nodeIdBuilder = nodesMap.get(nodesKeys[replicaIndex]);
						System.out.println("Replica Index :: " + replicaIndex);
						System.out.println("Replica Details :: " + nodeIdBuilder.toString());

						Cassandra.ReadRequestFromCoordinator.Builder readRequestFromCoodinatorObj = Cassandra.ReadRequestFromCoordinator.newBuilder();
						readRequestFromCoodinatorObj.setKey(key);
						readRequestFromCoodinatorObj.setTimeStamp(Long.toString(timeStampInMillis));
						readRequestFromCoodinatorObj.setCoordinatorName(currentNodeName);

						Cassandra.CoordinatorMessage.Builder messageFromCoordinator = Cassandra.CoordinatorMessage.newBuilder();
						messageFromCoordinator.setReadRequestFromCoordinator(readRequestFromCoodinatorObj);
						messageFromCoordinator.build();

						Socket requestFromCoordinatorToReplicaSocket = null;
						try {
							requestFromCoordinatorToReplicaSocket = new Socket(nodeIdBuilder.getIp(), nodeIdBuilder.getPort());
							messageFromCoordinator.build().writeDelimitedTo(requestFromCoordinatorToReplicaSocket.getOutputStream());
							messageCounter++;
						} catch (Exception e) {
							// handling down replica scenario

							System.err.println("Unable to connect Node : " + nodeIdBuilder.getName());

//							if (hintedHandOffMap.containsKey(nodeIdBuilder.getName())) {
//								List<Cassandra.CoordinatorMessage.Builder> hintedHandOffListForExistingNode = hintedHandOffMap.get(nodeIdBuilder.getName());
//
//								hintedHandOffListForExistingNode.add(messageFromCoordinator);
//
//								hintedHandOffMap.put(nodeIdBuilder.getName(), hintedHandOffListForExistingNode);
//							} else {
//								List<Cassandra.CoordinatorMessage.Builder> hintedHandOffList = new ArrayList<>();
//								hintedHandOffList.add(messageFromCoordinator);
//								hintedHandOffMap.put(nodeIdBuilder.getName(), hintedHandOffList);
//							}

						}
						if (requestFromCoordinatorToReplicaSocket != null) {
							requestFromCoordinatorToReplicaSocket.close();
						}
					}
//					System.out.println("Message Counter : " + messageCounter);
//					System.out.println("Consistency Level : " + consistencyLevel);

					// handling down replica
					if (messageCounter < consistencyLevel) {
//						System.out.println("Inside message counter");
						Cassandra.ResponseToClient.Builder responseToClientObj = Cassandra.ResponseToClient.newBuilder();
						responseToClientObj.setKey(key);
						responseToClientObj.setValue("");
						responseToClientObj.setMessage(consistencyErrorMessage);
						responseToClient(clientReadSocket, responseToClientObj);
					}

//					List<Cassandra.NodeId.Builder> replicaNodeIdList = findReplicas(key);
//					Cassandra.KeyValuePair.Builder keyValuePairObj = getKeyFromMemtable(key);
//
//					responseToClient(socket, keyValuePairObj);

				} else if (coordinatorMessage.hasReadRequestFromCoordinator()) {
					System.out.println("============== Read Request From Coordinator to Replica  ============== ");

					int key = coordinatorMessage.getReadRequestFromCoordinator().getKey();
					String coordinatorName = coordinatorMessage.getReadRequestFromCoordinator().getCoordinatorName();
					String timestamp = coordinatorMessage.getReadRequestFromCoordinator().getTimeStamp();

					if (isHintedHandoff) {
						// Logic to handle hintedHandOff
						processHintedHandOff(coordinatorName);
//						System.out.println("HintedHandOffMode Handled done!");
					}
					Cassandra.ResponseToCoordinator.Builder responseToCoordinatorBuilder = Cassandra.ResponseToCoordinator.newBuilder();

					responseToCoordinatorBuilder.setKey(key);
					responseToCoordinatorBuilder.setReplicaName(currentNodeName);
					responseToCoordinatorBuilder.setRequestType(Cassandra.RequestType.READ);
					responseToCoordinatorBuilder.setCoordinatorTimeStamp(timestamp);

					if (currentKeyValuePairMap.containsKey(key)) {
						Cassandra.KeyValuePair.Builder keyValuePairBuilder = currentKeyValuePairMap.get(key);
						String value = keyValuePairBuilder.getValue();
						String timeStamp = keyValuePairBuilder.getTimeStamp();
//						System.out.println(" keyValuePairBuilder : " + keyValuePairBuilder.toString());

						responseToCoordinatorBuilder.setReplicaTimeStamp(timeStamp);
						responseToCoordinatorBuilder.setValue(value);
					} else {
						responseToCoordinatorBuilder.setValue("");
						responseToCoordinatorBuilder.setMessage("Unable to find key " + key);
					}

					Cassandra.CoordinatorMessage.Builder messageToCoordinator = Cassandra.CoordinatorMessage.newBuilder();
					messageToCoordinator.setResponseToCoordinator(responseToCoordinatorBuilder);

					Cassandra.NodeId.Builder nodeIdBuilder = nodesMap.get(coordinatorName);
					Socket requestFromReplicaToCoordinatorSocket = new Socket(nodeIdBuilder.getIp(), nodeIdBuilder.getPort());

					messageToCoordinator.build().writeDelimitedTo(requestFromReplicaToCoordinatorSocket.getOutputStream());
					if (requestFromReplicaToCoordinatorSocket != null) {
						requestFromReplicaToCoordinatorSocket.close();
					}
				} else if (coordinatorMessage.hasResponseToCoordinator()) {

					System.out.println(" ============== Retrieving Response from Replica to Coordinator  ============== ");
					System.out.println("coordinatorMessage.getResponseToCoordinator() :::  \n" + coordinatorMessage.getResponseToCoordinator().toString());
					int key = coordinatorMessage.getResponseToCoordinator().getKey();
					String value = coordinatorMessage.getResponseToCoordinator().getValue();
					String replicaName = coordinatorMessage.getResponseToCoordinator().getReplicaName();
					String requestType = coordinatorMessage.getResponseToCoordinator().getRequestType().toString();
					String coordinatorTimeStamp = coordinatorMessage.getResponseToCoordinator().getCoordinatorTimeStamp();
					String replicaTimeStamp = coordinatorMessage.getResponseToCoordinator().getReplicaTimeStamp();

					Cassandra.ResponseToCoordinator.Builder responseToCoordinatorBuilder = Cassandra.ResponseToCoordinator.newBuilder();
					responseToCoordinatorBuilder.setKey(key);
					responseToCoordinatorBuilder.setValue(value);
					responseToCoordinatorBuilder.setReplicaName(replicaName);
					responseToCoordinatorBuilder.setRequestType(coordinatorMessage.getResponseToCoordinator().getRequestType());
					responseToCoordinatorBuilder.setCoordinatorTimeStamp(coordinatorTimeStamp);
					responseToCoordinatorBuilder.setReplicaTimeStamp(replicaTimeStamp);

					responseFromReplicasMap.put(responseToCoordinatorBuilder.getReplicaTimeStamp(), responseToCoordinatorBuilder);

					System.out.println("------------------------------------");

					System.out.println("Response From Replicas Map Content :: \n" + responseFromReplicasMap.toString());

					System.out.println("------------------------------------");

					Cassandra.ResponseToCoordinator.Builder latestTimeStampKeyEntry = null;

					noOfReplicaResponseReceived++;

					if (responseFromReplicasMap != null && noOfReplicaResponseReceived >= consistencyLevel) {

						String lastKey = responseFromReplicasMap.lastKey();
						latestTimeStampKeyEntry = responseFromReplicasMap.get(lastKey);

						System.out.println("\nLast element from ResponseFromReplicasMap : \n " + latestTimeStampKeyEntry);

						Cassandra.ResponseToClient.Builder responseToClientObj = Cassandra.ResponseToClient.newBuilder();
						responseToClientObj.setKey(latestTimeStampKeyEntry.getKey());

						if (latestTimeStampKeyEntry.getValue() != "") {
							responseToClientObj.setValue(latestTimeStampKeyEntry.getValue());
							responseToClientObj.setMessage("Key Found on Node : " + latestTimeStampKeyEntry.getReplicaName());
						} else {
							responseToClientObj.setMessage("Unable to find key " + key);
						}

						if (clientReadSocket != null) {
							responseToClient(clientReadSocket, responseToClientObj);
						}
					}

					System.out.println("replicaFactor : " + replicaFactor);
					System.out.println("isreadRepair : " + isreadRepair);
					Thread.sleep(100);
					TreeMap<String, Cassandra.ResponseToCoordinator.Builder> responseFromReplicasMapForReadReapir = new TreeMap<>();
					String lastKey = responseFromReplicasMap.lastKey();
					Cassandra.ResponseToCoordinator.Builder latestTimeStampKeyValueEntry = responseFromReplicasMap.get(lastKey);

					for (String replicaTimestampKeys : responseFromReplicasMap.keySet()) {
						String replicaTimestamp = responseFromReplicasMap.get(replicaTimestampKeys).getReplicaTimeStamp();
						if (replicaTimestamp.compareTo(latestTimeStampKeyValueEntry.getReplicaTimeStamp()) != 0) {
							// Entries needed Read repair
							responseFromReplicasMapForReadReapir.put(replicaTimestampKeys, responseFromReplicasMap.get(replicaTimestampKeys));
						}
					}

					System.out.println("------------------------------------------");
					System.out.println("List of Replica which need read Repair : " + responseFromReplicasMapForReadReapir.toString());
					System.out.println("------------------------------------------");

					if (responseFromReplicasMap != null && isreadRepair) {
						performReadRepairOperation(responseFromReplicasMapForReadReapir, latestTimeStampKeyValueEntry);
						System.out.println(" ============== performReadRepairOperation done  ============== ");
					}
//					System.out.println("Details from hasResponseToCoordinator ::: ");
//					System.out.println("key : " + key + " Value : " + value + " coordinatorTimeStamp : " + coordinatorTimeStamp + " replicaTimeStamp : "
//							+ replicaTimeStamp);
//					Cassandra.ResponseToClient.Builder responseToClientBuilder = Cassandra.ResponseToClient.newBuilder();

				} else if (coordinatorMessage.hasWriteRequestFromCoordinator()) {
					System.out.println(" ============== Read Repair for Replica and Inserting to MemTable ============== ");

					int key = coordinatorMessage.getWriteRequestFromCoordinator().getKey();
					String latestValue = coordinatorMessage.getWriteRequestFromCoordinator().getValue();
					String latestTimestamp = coordinatorMessage.getWriteRequestFromCoordinator().getTimeStamp();

					if (isHintedHandoff) {
						// Logic to handle hintedHandOff
						processHintedHandOff(coordinatorMessage.getWriteRequestFromCoordinator().getCoordinatorName());
						System.out.println("Inside write request from Coordinator : HintedHandOffMode Handled done!");
					}

					if (currentKeyValuePairMap.containsKey(key)) {
						// If for key there is already a entry in the keyValuePairBuilderObj then have
						// to update that entry
						if (currentKeyValuePairMap.get(key).getTimeStamp().compareTo(latestTimestamp) < 0) {
							currentKeyValuePairMap.get(key).setTimeStamp(latestTimestamp).setValue(latestValue);
						}
					} else {
						// If for key there is no entry in the keyValuePairBuilderObj then have to add
						// this as new entry
						Cassandra.KeyValuePair.Builder keyValuePairBuilderObj = Cassandra.KeyValuePair.newBuilder();
						keyValuePairBuilderObj.setKey(key);
						keyValuePairBuilderObj.setValue(latestValue);
						keyValuePairBuilderObj.setTimeStamp(latestTimestamp);
						currentKeyValuePairMap.put(key, keyValuePairBuilderObj);

					}
					System.out.println("Updated KeyValuePairMap :: \n" + "Value : " + currentKeyValuePairMap.get(key).getValue());
					System.out.println("Timestamp : " + currentKeyValuePairMap.get(key).getTimeStamp());

					writeToWAL();

				} else if (coordinatorMessage.hasWriteRequestFromClient()) {
					System.out.println(" ============== Write Request From Client to Coordinator  ============== ");

					clientWriteSocket = socket;

					int keyToWrite = coordinatorMessage.getWriteRequestFromClient().getKey();
					String consistencyLevelTypeToWrite = coordinatorMessage.getWriteRequestFromClient().getConsistencyLevel().toString();
					String valueToWrite = coordinatorMessage.getWriteRequestFromClient().getValue();

					if (consistencyLevelTypeToWrite.equalsIgnoreCase("ONE")) {
						consistencyLevel = 1;
					} else if (consistencyLevelTypeToWrite.equalsIgnoreCase("QUORUM")) {
						consistencyLevel = (replicaFactor / 2) + 1; // In case of QUORUM, it has to be majority
					}
					int primaryReplicaIndex = (keyToWrite * nodesMap.size()) / 256;

					String[] nodesKeys = nodesMap.keySet().toArray(new String[nodesMap.size()]);

					long timeStampInMillis = Instant.now().toEpochMilli();

					int messageCounter = 0;

					for (int i = 0; i < replicaFactor; i++) {

						int replicaIndex = (primaryReplicaIndex + nodesMap.size() + i) % nodesMap.size();
						Cassandra.NodeId.Builder nodeIdBuilder = nodesMap.get(nodesKeys[replicaIndex]);
						System.out.println("From WriteRequestFromClient  : Rest Replica Index :: " + replicaIndex);
						System.out.println("From WriteRequestFromClient  : Replica Details :: " + nodeIdBuilder.toString());

						Cassandra.WriteRequestFromCoordinator.Builder writeRequestFromCoodinatorObj = Cassandra.WriteRequestFromCoordinator.newBuilder();
						writeRequestFromCoodinatorObj.setKey(keyToWrite);
						writeRequestFromCoodinatorObj.setValue(valueToWrite);
						writeRequestFromCoodinatorObj.setTimeStamp(Long.toString(timeStampInMillis));
						writeRequestFromCoodinatorObj.setIsReadRepair(false);
						writeRequestFromCoodinatorObj.setCoordinatorName(currentNodeName);

						Cassandra.CoordinatorMessage.Builder messageFromCoordinator = Cassandra.CoordinatorMessage.newBuilder();
						messageFromCoordinator.setWriteRequestFromCoordinator(writeRequestFromCoodinatorObj);
						messageFromCoordinator.build();

						Socket requestFromCoordinatorToReplicaSocket = null;

						try {
							requestFromCoordinatorToReplicaSocket = new Socket(nodeIdBuilder.getIp(), nodeIdBuilder.getPort());
							messageFromCoordinator.build().writeDelimitedTo(requestFromCoordinatorToReplicaSocket.getOutputStream());
							messageCounter++;

						} catch (Exception e) {
							// handling down replica scenario
							System.err.println("Unable to connect Node : " + nodeIdBuilder.getName());

							if (hintedHandOffMap.containsKey(nodeIdBuilder.getName())) {
								List<Cassandra.CoordinatorMessage.Builder> hintedHandOffListForExistingNode = hintedHandOffMap.get(nodeIdBuilder.getName());
							
								hintedHandOffListForExistingNode.add(messageFromCoordinator);
								
								hintedHandOffMap.put(nodeIdBuilder.getName(), hintedHandOffListForExistingNode);
							
							} else {
								List<Cassandra.CoordinatorMessage.Builder> hintedHandOffList = new ArrayList<>();
								hintedHandOffList.add(messageFromCoordinator);
								hintedHandOffMap.put(nodeIdBuilder.getName(), hintedHandOffList);
							}
							writeToHintedHandOffLog();
							System.out.println("Down Replica map :: \n" + hintedHandOffMap.toString());
						}
						if (requestFromCoordinatorToReplicaSocket != null) {
							requestFromCoordinatorToReplicaSocket.close();
						}
					}
//					System.out.println("Message Counter : " + messageCounter);
//					System.out.println("Consistency Level : " + consistencyLevel);

					if (messageCounter < consistencyLevel) {
						Cassandra.ResponseToClient.Builder responseToClientObj = Cassandra.ResponseToClient.newBuilder();
						responseToClientObj.setKey(keyToWrite);
						responseToClientObj.setValue(valueToWrite);
						responseToClientObj.setMessage(consistencyErrorMessage);
						System.out.println("clientWriteSocket :: " + clientWriteSocket.getPort() + " ========= " + clientWriteSocket.toString());
						responseToClient(clientWriteSocket, responseToClientObj);
					} else {
						Cassandra.ResponseToClient.Builder responseToClientObj = Cassandra.ResponseToClient.newBuilder();
						responseToClientObj.setKey(keyToWrite);
						responseToClientObj.setValue(valueToWrite);
						responseToClientObj.setMessage("Successfully added key " + keyToWrite + " to table");
						responseToClient(clientWriteSocket, responseToClientObj);
					}
				}
			}
		} catch (IOException ioe) {
			System.err.println(ioe.getMessage());
			ioe.printStackTrace();
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}

	}

	private static synchronized void performReadRepairOperation(TreeMap<String, Cassandra.ResponseToCoordinator.Builder> responseFromReplicasMapForReadReapir,
			Cassandra.ResponseToCoordinator.Builder latestTimeStampKeyValueEntry) {
		try {

			System.out.println("\nLast element (which has latest timestamp) from responseFromReplicasMap : \n " + latestTimeStampKeyValueEntry);
			for (String replicaTimeStampofEach : responseFromReplicasMapForReadReapir.keySet()) {

//				String replicaTimestamp = latestTimeStampKeyValueEntry.getReplicaTimeStamp();
//				String rTimeStamp = responseFromReplicasMap.get(replicaTimeStampofEach).getReplicaTimeStamp();
//
//				System.out.println("latestTimeStampKeyValueEntry.getReplicaTimeStamp() " + replicaTimestamp);
//				System.out.println("responseFromReplicasMap.get(timeStampKey).getReplicaTimeStamp() : " + rTimeStamp);
//
//				System.out.println("Comapre To ::::::::::::::::::::  " + replicaTimestamp.compareTo(rTimeStamp));

				if (!latestTimeStampKeyValueEntry.getReplicaTimeStamp()
						.equalsIgnoreCase(responseFromReplicasMap.get(replicaTimeStampofEach).getReplicaTimeStamp())) {
					// Read Repair Needed for this replica

					Cassandra.WriteRequestFromCoordinator.Builder writeRequestFromCoordinatorBuilderObj = Cassandra.WriteRequestFromCoordinator.newBuilder();
					writeRequestFromCoordinatorBuilderObj.setKey(latestTimeStampKeyValueEntry.getKey());
					writeRequestFromCoordinatorBuilderObj.setTimeStamp(latestTimeStampKeyValueEntry.getReplicaTimeStamp());
					writeRequestFromCoordinatorBuilderObj.setValue(latestTimeStampKeyValueEntry.getValue());
					writeRequestFromCoordinatorBuilderObj.setCoordinatorName(latestTimeStampKeyValueEntry.getReplicaName()).setIsReadRepair(true);

					Cassandra.CoordinatorMessage.Builder coordinatorMessageBuilderObj = Cassandra.CoordinatorMessage.newBuilder();
					coordinatorMessageBuilderObj.setWriteRequestFromCoordinator(writeRequestFromCoordinatorBuilderObj).build();

					Cassandra.NodeId.Builder nodeIdForReadRepairReplica = nodesMap.get(responseFromReplicasMap.get(replicaTimeStampofEach).getReplicaName());

					Socket readRepairSocketToReplica = new Socket(nodeIdForReadRepairReplica.getIp(), nodeIdForReadRepairReplica.getPort());

					coordinatorMessageBuilderObj.build().writeDelimitedTo(readRepairSocketToReplica.getOutputStream());
					if (readRepairSocketToReplica != null) {
						readRepairSocketToReplica.close();
					}
				}
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

	private static void responseToClient(Socket socket, Cassandra.ResponseToClient.Builder responseToClientObj) throws IOException {

		try {
			Cassandra.CoordinatorMessage.Builder messageToCoordinator = Cassandra.CoordinatorMessage.newBuilder();
			messageToCoordinator.setResponseToClient(responseToClientObj);
			System.out.println("From responseToClient block:: Ready to send the message");
			System.out.println("messageToCoordinator :::::::: " + messageToCoordinator.toString());
			if (socket != null && !socket.isClosed()) {
				messageToCoordinator.build().writeDelimitedTo(socket.getOutputStream());
				socket.close();
				System.out.println("Message sent to client done!");
			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

//	private static void populateMemoryTable() {
//
//		for (int i = 0; i < 10; i++) {
//			Cassandra.KeyValuePair.Builder keyValuePairObj = Cassandra.KeyValuePair.newBuilder();
//			long timeStampInMillis = Instant.now().toEpochMilli();
//
//			keyValuePairObj.setKey(i);
//			keyValuePairObj.setValue("Value-" + i);
//			keyValuePairObj.setTimeStamp(Long.toString(timeStampInMillis + i));
//			currentKeyValuePairMap.put(keyValuePairObj.getKey(), keyValuePairObj);
//		}
//	}

	private static Cassandra.KeyValuePair.Builder getKeyFromMemtable(int key) {
		Cassandra.KeyValuePair.Builder keyValuePairObj = null;
		keyValuePairObj = currentKeyValuePairMap.get(key);
		return keyValuePairObj;
	}

	private static List<Cassandra.NodeId.Builder> findReplicas(int key) {
		List<Cassandra.NodeId.Builder> replicaNodeIdList = new ArrayList<>();
		int primaryReplicaIndex = key * nodesMap.size() / 256;
		int firstReplicaIndex = (primaryReplicaIndex + nodesMap.size() + 1) % nodesMap.size();
		int secondReplicaIndex = (primaryReplicaIndex + nodesMap.size() + 2) % nodesMap.size();
		System.out.println("Primary_Replica_Index  : " + primaryReplicaIndex + " First_Replica_Index : " + firstReplicaIndex + " Second_Replica_Index : "
				+ secondReplicaIndex);

		Cassandra.NodeId.Builder primaryReplicaNodeId = nodesMap.get(primaryReplicaIndex);
		Cassandra.NodeId.Builder firstReplicaNodeId = nodesMap.get(firstReplicaIndex);
		Cassandra.NodeId.Builder secondReplicaNodeId = nodesMap.get(secondReplicaIndex);
		replicaNodeIdList.add(primaryReplicaNodeId);
		replicaNodeIdList.add(firstReplicaNodeId);
		replicaNodeIdList.add(secondReplicaNodeId);
		return replicaNodeIdList;
	}

	private static void processHintedHandOff(String coordinatorName) {

		if (hintedHandOffMap.containsKey(coordinatorName)) {
			System.out.println("processHintedHandOff hintedHandOffMap :: " + hintedHandOffMap.toString());
			List<Cassandra.CoordinatorMessage.Builder> pendingMessagesList = hintedHandOffMap.get(coordinatorName);
			System.out.println("processHintedHandOff pendingMessagesList :: " + pendingMessagesList.toString());

			try {
				for (Cassandra.CoordinatorMessage.Builder pendingMessage : pendingMessagesList) {
					try {

						System.out.println("PendingMessage IP : " + nodesMap.get(coordinatorName).getIp() + " PORT : " + nodesMap.get(coordinatorName).getPort());
						Socket pendingMessageSocket = new Socket(nodesMap.get(coordinatorName).getIp(), nodesMap.get(coordinatorName).getPort());
						pendingMessage.build().writeDelimitedTo(pendingMessageSocket.getOutputStream());
						if (pendingMessageSocket != null) {
							pendingMessageSocket.close();
						}
//						pendingMessagesList.remove(pendingMessage);
					} catch (IOException e) {
						System.err.println(e.getMessage());
						e.printStackTrace();
					}
				}
				hintedHandOffMap.remove(coordinatorName);
			} catch (Exception e) {
				System.err.println(e.getMessage());
				e.printStackTrace();
			}
		}

	}

	private static void writeToWAL() {
		try {
			File logDir = new File(WAL_DIRECTORY_NAME);
			if (!logDir.exists()) {
				logDir.mkdir();
			}
			String userDir = System.getProperty("user.dir");
			System.out.println("User dir  : " + userDir);
			String writeAheadLogFilePath = userDir + "/" + WAL_DIRECTORY_NAME + "/" + currentNodeName + "_WAL_Log.txt";

			File file = new File(writeAheadLogFilePath);
			file.createNewFile();
			StringBuilder stringBuilder = new StringBuilder();

			for (Integer key : currentKeyValuePairMap.keySet()) {
				stringBuilder.append(key).append(" ").append(currentKeyValuePairMap.get(key).getValue()).append(" ")
						.append(currentKeyValuePairMap.get(key).getTimeStamp()).append("\n");
			}
			BufferedWriter bufferedWriter = null;
			FileWriter fileWrite = null;
			fileWrite = new FileWriter(file, false);
			bufferedWriter = new BufferedWriter(fileWrite);
			bufferedWriter.write(stringBuilder.toString());
			bufferedWriter.flush();
			bufferedWriter.close();

		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

	private static void readFromWAL() {
		try {
			String userDir = System.getProperty("user.dir");
			String writeAheadLogFilePath = userDir + "/" + WAL_DIRECTORY_NAME + "/" + currentNodeName + "_WAL_Log.txt";

			File file = new File(writeAheadLogFilePath);
			if (file.exists()) {
				// read from file
				FileReader fileReader = new FileReader(file);
				BufferedReader bufferedReader = new BufferedReader(fileReader);
				String line;
				while ((line = bufferedReader.readLine()) != null) {

					Integer keyFromFile = Integer.parseInt(line.split(" ")[0].trim());
					String valueFromFile = line.split(" ")[1].trim();
					String timestampFromFile = line.split(" ")[2].trim();

					Cassandra.KeyValuePair.Builder builder = Cassandra.KeyValuePair.newBuilder();
					builder.setKey(keyFromFile);
					builder.setValue(valueFromFile);
					builder.setTimeStamp(timestampFromFile);
					currentKeyValuePairMap.put(keyFromFile, builder);

				}
				fileReader.close();
				bufferedReader.close();
			}
		} catch (

		Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

	private static void writeToHintedHandOffLog() {
		try {
			File logDir = new File(HHF_DIRECTORY_NAME);
			if (!logDir.exists()) {
				logDir.mkdir();
			}
			String userDir = System.getProperty("user.dir");
			System.out.println("User dir  : " + userDir);
			String hhoffLogFilePath = userDir + "/" + HHF_DIRECTORY_NAME + "/" + currentNodeName + "_hhf_Log.txt";

			File file = new File(hhoffLogFilePath);
			file.createNewFile();
			StringBuilder stringBuilder = new StringBuilder();

			for (String replicaNameKey : hintedHandOffMap.keySet()) {

				List<Cassandra.CoordinatorMessage.Builder> coordinatorMessageList = hintedHandOffMap.get(replicaNameKey);

				for (Cassandra.CoordinatorMessage.Builder coordinatorMessage : coordinatorMessageList) {

					Integer coordinatorMessageKey = coordinatorMessage.getWriteRequestFromCoordinator().getKey();
					String value = coordinatorMessage.getWriteRequestFromCoordinator().getValue();
					String coordinatorName = coordinatorMessage.getWriteRequestFromCoordinator().getCoordinatorName();
					String timestamp = coordinatorMessage.getWriteRequestFromCoordinator().getTimeStamp();
					boolean isReadRepair = coordinatorMessage.getWriteRequestFromCoordinator().getIsReadRepair();
					stringBuilder
							.append(replicaNameKey + " " + coordinatorMessageKey + " " + value + " " + coordinatorName + " " + timestamp + " " + isReadRepair)
							.append("\n");
				}
			}
			BufferedWriter bufferedWriter = null;
			FileWriter fileWrite = null;
			fileWrite = new FileWriter(file, false);
			bufferedWriter = new BufferedWriter(fileWrite);
			bufferedWriter.write(stringBuilder.toString());
			bufferedWriter.flush();
			bufferedWriter.close();

		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}

	private static void readHintedHandOffLog() {
		try {

			String userDir = System.getProperty("user.dir");
			System.out.println("User dir  : " + userDir);
			String hhoffLogFilePath = userDir + "/" + HHF_DIRECTORY_NAME + "/" + currentNodeName + "_hhf_Log.txt";
			File file = new File(hhoffLogFilePath);
			if (file.exists()) {

				FileReader fileReader = new FileReader(file);
				BufferedReader bufferedReader = new BufferedReader(fileReader);
				String line;
				while ((line = bufferedReader.readLine()) != null) {

					String replicaNameKey = line.split(" ")[0].trim();
					Integer coordinatorMessageKey = Integer.parseInt(line.split(" ")[1].trim());
					String value = line.split(" ")[2].trim();
					String coordinatorName = line.split(" ")[3].trim();
					String timestamp = line.split(" ")[4].trim();
					boolean isReadRepair = Boolean.parseBoolean(line.split(" ")[5].trim());

					Cassandra.WriteRequestFromCoordinator.Builder writeRequestFromCoodinatorObj = Cassandra.WriteRequestFromCoordinator.newBuilder();
					writeRequestFromCoodinatorObj.setKey(coordinatorMessageKey);
					writeRequestFromCoodinatorObj.setValue(value);
					writeRequestFromCoodinatorObj.setTimeStamp(timestamp);
					writeRequestFromCoodinatorObj.setIsReadRepair(isReadRepair);
					writeRequestFromCoodinatorObj.setCoordinatorName(coordinatorName);

					Cassandra.CoordinatorMessage.Builder messageFromCoordinator = Cassandra.CoordinatorMessage.newBuilder();
					messageFromCoordinator.setWriteRequestFromCoordinator(writeRequestFromCoodinatorObj);
					messageFromCoordinator.build();

					if (hintedHandOffMap.containsKey(replicaNameKey)) {
						List<Cassandra.CoordinatorMessage.Builder> hintedHandOffListForExistingNode = hintedHandOffMap.get(replicaNameKey);
						hintedHandOffListForExistingNode.add(messageFromCoordinator);
						hintedHandOffMap.put(replicaNameKey, hintedHandOffListForExistingNode);
					} else {
						List<Cassandra.CoordinatorMessage.Builder> hintedHandOffList = new ArrayList<>();
						hintedHandOffList.add(messageFromCoordinator);
						hintedHandOffMap.put(replicaNameKey, hintedHandOffList);
					}
				}
				fileReader.close();
				bufferedReader.close();

			}
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}
	}
}
