import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

/**
 * @author aaruj1
 *
 */
public class Client {

	private static Map<String, Cassandra.NodeId.Builder> nodes = new TreeMap<>();

	public static void main(String[] args) {

		try {
			if (args.length != 1) {
				System.err.println("Please provide :  <nodes-filename>");
				System.exit(0);
			}
			String replicaFileName = args[0];
			File file = new File(replicaFileName);
			if (!file.exists()) {
				System.err.println("File does not exist.");
				System.exit(0);
			}
			String line = null;
			BufferedReader bufferedReader = new BufferedReader(new FileReader(file));

			while ((line = bufferedReader.readLine()) != null) {
				String nodeName = line.split(" ")[0].trim();
				String nodeIPAddress = line.split(" ")[1].trim();
				int nodePort = Integer.parseInt(line.split(" ")[2].trim());
				Cassandra.NodeId.Builder nodeObj = Cassandra.NodeId.newBuilder();
				nodeObj.setName(nodeName);
				nodeObj.setIp(nodeIPAddress);
				nodeObj.setPort(nodePort);
				nodes.put(nodeObj.getName(), nodeObj);
			}

			if (nodes == null) {
				System.err.println("Nodes file is empty.");
				System.exit(0);
			}

			if (bufferedReader != null) {
				bufferedReader.close();
			}

			while (true) {
				System.out.println("Enter the read/write request : Read - <nodename> <read> <key> <consistency-level>");
				System.out.println("Write - <nodename> <write> <key> <value> <consistency-level>");
				Scanner scanner = new Scanner(System.in);
				String requestString = scanner.nextLine();
				String[] readOrWriteRequest = requestString.split(" ");
				if (readOrWriteRequest != null && readOrWriteRequest.length > 0) {

					String nodeName = readOrWriteRequest[0];
					String requestType = readOrWriteRequest[1];
					int key = Integer.parseInt(readOrWriteRequest[2]);
					String consistencyLevel = "";

					if (!nodes.containsKey(nodeName)) {
						System.err.println("Node name not found in file.");
					} else {
						if (requestType.equalsIgnoreCase(Cassandra.RequestType.READ.toString())) {

							// GET Request from client and send To Coordinator
							consistencyLevel = readOrWriteRequest[3];
							if (consistencyLevel.equalsIgnoreCase(Cassandra.ConsistencyLevel.ONE.toString())) {
								getOperation(nodeName, requestType, key, Cassandra.ConsistencyLevel.ONE);

							} else if (consistencyLevel.equalsIgnoreCase(Cassandra.ConsistencyLevel.QUORUM.toString())) {
//							System.out.println("READ - For Consistency Level :  QUORUM");
								getOperation(nodeName, requestType, key, Cassandra.ConsistencyLevel.QUORUM);
							}

						} else if (requestType.equalsIgnoreCase(Cassandra.RequestType.WRITE.toString())) {

							// PUT Request from client To Coordinator
							String value = readOrWriteRequest[3];
							consistencyLevel = readOrWriteRequest[4];
							if (consistencyLevel.equalsIgnoreCase(Cassandra.ConsistencyLevel.ONE.toString())) {
								putOperation(nodeName, requestType, key, value, Cassandra.ConsistencyLevel.ONE);
							} else if (consistencyLevel.equalsIgnoreCase(Cassandra.ConsistencyLevel.QUORUM.toString())) {
								putOperation(nodeName, requestType, key, value, Cassandra.ConsistencyLevel.QUORUM);
							}
						}
					}
				}
			}
		} catch (Exception ex) {
			System.err.println(ex.getMessage());
			ex.printStackTrace();
			System.exit(0);
		}
	}

	private static void putOperation(String nodeName, String requestType, int key, String value, Cassandra.ConsistencyLevel consistencyLevel) {
		try {

			String ipAddress = nodes.get(nodeName).getIp();
			int port = nodes.get(nodeName).getPort();
			Socket socket = new Socket(ipAddress, port);
			Cassandra.WriteRequestFromClient.Builder writeRequestObj = Cassandra.WriteRequestFromClient.newBuilder();
			writeRequestObj.setKey(key);
			writeRequestObj.setValue(value);
			writeRequestObj.setConsistencyLevel(consistencyLevel);
			Cassandra.CoordinatorMessage.Builder coordinatorMessageBuilder = Cassandra.CoordinatorMessage.newBuilder();
			coordinatorMessageBuilder.setWriteRequestFromClient(writeRequestObj);
			coordinatorMessageBuilder.build().writeDelimitedTo(socket.getOutputStream());
			Cassandra.CoordinatorMessage coordinatorMessageBuilderResponse = Cassandra.CoordinatorMessage.parseDelimitedFrom(socket.getInputStream());

			System.out.println("Resoponse from Coordinator for PUT opertaion : \n");
			System.out.println("Key : " + coordinatorMessageBuilderResponse.getResponseToClient().getKey());
			System.out.println("Value : " + coordinatorMessageBuilderResponse.getResponseToClient().getValue());
			System.out.println("Message : " + coordinatorMessageBuilderResponse.getResponseToClient().getMessage());

			if (socket != null && !socket.isClosed()) {
				socket.close();
			}
			System.out.println("coordinatorMessageBuilderResponse :: " + coordinatorMessageBuilderResponse.toString());
		} catch (UnknownHostException e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}

	}

	private static void getOperation(String nodeName, String requestType, int key, Cassandra.ConsistencyLevel consistencyLevel) {
		try {

			String ipAddress = nodes.get(nodeName).getIp();
			int port = nodes.get(nodeName).getPort();
			System.out.println("IP Address : " + ipAddress + " Port : " + port);
			Socket socket = new Socket(ipAddress, port);
			Cassandra.ReadRequestFromClient.Builder readRequestObj = Cassandra.ReadRequestFromClient.newBuilder();
			readRequestObj.setKey(key);
			readRequestObj.setConsistencyLevel(consistencyLevel);

			Cassandra.CoordinatorMessage.Builder coordinatorMessageBuilder = Cassandra.CoordinatorMessage.newBuilder();
			coordinatorMessageBuilder.setReadRequestFromClient(readRequestObj);
			coordinatorMessageBuilder.build().writeDelimitedTo(socket.getOutputStream());
			// Incoming response from coordinator
			if (socket != null && !socket.isClosed()) {
				System.out.println("Listening from coordinator");
				Cassandra.CoordinatorMessage coordinatorMessage = Cassandra.CoordinatorMessage.parseDelimitedFrom(socket.getInputStream());
				System.out.println("Resoponse from Coordinator for GET operation: \n");
				System.out.println("Key : " + coordinatorMessage.getResponseToClient().getKey());
				System.out.println("Value : " + coordinatorMessage.getResponseToClient().getValue());
				System.out.println("Message : " + coordinatorMessage.getResponseToClient().getMessage());

				socket.close();
			}
		} catch (UnknownHostException e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		} catch (Exception e) {
			System.err.println(e.getMessage());
			e.printStackTrace();
		}

	}

}
