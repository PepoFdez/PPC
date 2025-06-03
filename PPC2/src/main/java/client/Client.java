package client;

import common.*;
import java.io.*;
import java.net.*;
// Imports de GSON y XML deben estar en MessageUtils y las clases de mensajes.

public class Client {
    private static final int BROADCAST_PORT = 5000;
    private volatile boolean running = true;
    private final String logFileReceived = "client_received_broadcast.log";
    private final String logFileSent = "client_sent_control.log";
    private final String logFileServerResponses = "client_received_server_response.log";

    // Para almacenar el target actual
    private InetAddress currentTargetServerAddress = null;
    private int currentTargetServerPort = -1;


    public static void main(String[] args) {
        new Client().start();
    }

    public void start() {
        System.out.println("Client starting...");
        try {
            currentTargetServerAddress = InetAddress.getLocalHost(); // Default inicial
        } catch (UnknownHostException e) {
            System.err.println("Could not resolve default localhost. Please set target server.");
        }
        new Thread(this::listenForBroadcasts).start();
        handleUserInput();
        System.out.println("Client shutting down.");
    }

    private void listenForBroadcasts() { // Sin cambios importantes
        try (DatagramSocket broadcastSocket = new DatagramSocket(BROADCAST_PORT)) {
            broadcastSocket.setSoTimeout(5000); 
            byte[] buffer = new byte[4096]; 

            System.out.println("Client listening for broadcasts on port " + BROADCAST_PORT);
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    broadcastSocket.receive(packet);
                    String rawData = new String(packet.getData(), 0, packet.getLength());

                    MessageUtils.logMessage(logFileReceived, "From " + packet.getAddress() + ":" + packet.getPort() + " - " + rawData);
                    /*System.out.println("\n--- Received Broadcast ---");
                    System.out.println("Raw: " + rawData.substring(0, Math.min(rawData.length(),100)) + "...");

                    if (!rawData.contains(":")) {
                        System.err.println("Received malformed broadcast (no type prefix): " + rawData);
                        continue;
                    }

                    DistributionMessage distMsg = DistributionMessage.deserialize(rawData);

                    if (distMsg != null) {
                        System.out.println("Decoded Distribution Message from Server: " + distMsg.getServerId());
                        System.out.println("  Timestamp: " + new java.util.Date(distMsg.getTimestamp()));
                        System.out.println("  Encoding: " + distMsg.getEncodingFormat());
                        for (WeatherVariable var : distMsg.getVariables()) {
                            System.out.println("  - " + var.toString());
                        }
                        if (distMsg.getEncodingFormat().equals(MessageUtils.ENCODING_XML)){
                            // La validación DTD se hace dentro de MessageUtils.parseXmlString
                            // y el mensaje de éxito se imprime en DistributionMessage.deserialize
                        }
                    } else {
                        System.err.println("Failed to parse broadcast message.");
                    }
                     System.out.println("--- End Broadcast ---");
                     */
                } catch (SocketTimeoutException e) {
                    // Normal timeout
                } catch (Exception e) {
                    if (running) {
                        System.err.println("Error receiving/parsing broadcast: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Broadcast listener socket error: " + e.getMessage());
        } finally {
            System.out.println("Broadcast listener stopped.");
        }
    }

    private void handleUserInput() {
        try (DatagramSocket unicastSocket = new DatagramSocket();
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {

            unicastSocket.setSoTimeout(5000); 

            System.out.println("Enter commands. Type 'HELP' for options, 'EXIT' to quit.");
            if(currentTargetServerAddress == null) {
                System.out.println("WARN: Target server address not set. Use TARGET_SERVER <ip> <port>");
            } else {
                System.out.println("Default target: " + currentTargetServerAddress.getHostAddress() + (currentTargetServerPort > 0 ? ":" + currentTargetServerPort : " (port not set, use TARGET_SERVER)"));
            }


            while (running) {
                System.out.print("\nClient Command > ");
                String inputLine = reader.readLine();
                if (inputLine == null || inputLine.trim().equalsIgnoreCase("EXIT")) {
                    running = false;
                    break;
                }

                String[] parts = inputLine.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) continue;

                String commandType = parts[0].toUpperCase();
                ControlMessage controlMsg = null;

                // Validar si tenemos un target antes de comandos que lo requieren
                boolean requiresTarget = !"HELP".equals(commandType) && !"TARGET_SERVER".equals(commandType);
                if (requiresTarget && (currentTargetServerAddress == null || currentTargetServerPort == -1)) {
                    System.err.println("Error: Target server IP or port not set. Use TARGET_SERVER <ip_address> <port> first.");
                    continue;
                }

                switch (commandType) {
                    case "HELP":
                        showHelp();
                        continue;

                    case "TARGET_SERVER": 
                        if (parts.length < 3) { // Ahora necesita IP y Puerto
                             System.out.println("Usage: TARGET_SERVER <ip_address> <port>");
                             continue;
                        }
                        try {
                            currentTargetServerAddress = InetAddress.getByName(parts[1]);
                            currentTargetServerPort = Integer.parseInt(parts[2]);
                            System.out.println("Target server set to " + currentTargetServerAddress.getHostAddress() + ":" + currentTargetServerPort);
                        } catch (UnknownHostException e) {
                            System.err.println("Invalid server address: " + parts[1]);
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid port number: " + parts[2]);
                        }
                        continue;

                    case "SET_ENCODING": 
                         if (parts.length < 3) { System.out.println("Usage: SET_ENCODING <TargetServerID> <JSON|XML>"); continue; }
                        controlMsg = new ControlMessage(commandType, parts[1]);
                        controlMsg.addParameter("encoding", parts[2].toUpperCase());
                        break;
                    // ... resto de casos para comandos de control (SET_FREQUENCY, etc.) igual que antes
                    // pero asegúrate de que el constructor de ControlMessage tome el commandType
                    case "SET_FREQUENCY": 
                        if (parts.length < 3) { System.out.println("Usage: SET_FREQUENCY <TargetServerID> <frequency_ms>"); continue; }
                        try {
                            controlMsg = new ControlMessage(commandType, parts[1]);
                            controlMsg.addParameter("frequency", Integer.parseInt(parts[2]));
                        } catch (NumberFormatException e) { System.err.println("Invalid frequency."); continue; }
                        break;
                    case "SET_UNIT": 
                        if (parts.length < 4) { System.out.println("Usage: SET_UNIT <TargetServerID> <VarName> <NewUnit>"); continue; }
                        controlMsg = new ControlMessage(commandType, parts[1]);
                        controlMsg.addParameter("variableName", parts[2]);
                        controlMsg.addParameter("newUnit", parts[3]);
                        break;
                    case "ACTIVATE_SERVER": 
                         if (parts.length < 2) { System.out.println("Usage: ACTIVATE_SERVER <TargetServerID>"); continue; }
                        controlMsg = new ControlMessage("TOGGLE_SENDING_DATA", parts[1]);
                        controlMsg.addParameter("active", true);
                        break;
                    case "DEACTIVATE_SERVER": 
                        if (parts.length < 2) { System.out.println("Usage: DEACTIVATE_SERVER <TargetServerID>"); continue; }
                        controlMsg = new ControlMessage("TOGGLE_SENDING_DATA", parts[1]);
                        controlMsg.addParameter("active", false);
                        break;
                    case "STOP_SERVER": 
                        if (parts.length < 2) { System.out.println("Usage: STOP_SERVER <TargetServerID>"); continue; }
                        controlMsg = new ControlMessage("STOP_SERVER_PROCESS", parts[1]);
                        break;
                    default:
                        System.out.println("Unknown command. Type 'HELP'.");
                        continue;
                }

                if (controlMsg != null) {
                    String serializedMsg = controlMsg.serialize(); 
                    byte[] sendBuffer = serializedMsg.getBytes();
                    DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, currentTargetServerAddress, currentTargetServerPort);
                    unicastSocket.send(sendPacket);
                    MessageUtils.logMessage(logFileSent, "To " + currentTargetServerAddress + ":" + currentTargetServerPort + " - " + serializedMsg);
                    System.out.println("Sent to " + currentTargetServerAddress.getHostAddress() + ":" + currentTargetServerPort + " -> " + serializedMsg);

                    byte[] receiveBuffer = new byte[1024];
                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    try {
                        unicastSocket.receive(receivePacket);
                        String responseData = new String(receivePacket.getData(), 0, receivePacket.getLength());
                        MessageUtils.logMessage(logFileServerResponses, "From " + receivePacket.getAddress() + ":" + receivePacket.getPort() + " - " + responseData);
                        ResponseMessage responseMsg = ResponseMessage.deserialize(responseData); 
                        System.out.println("Server Response from " + receivePacket.getAddress().getHostAddress() + ":");
                        System.out.println("  Status: " + responseMsg.getStatus());
                        System.out.println("  Details: " + responseMsg.getDetails());
                    } catch (SocketTimeoutException e) {
                        System.err.println("No response from server " + currentTargetServerAddress.getHostAddress() + " (timeout).");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Client user input/unicast socket error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            running = false; 
            System.out.println("User input handler stopped.");
        }
    }

    private void showHelp() { // Actualizar HELP
        System.out.println("Available commands:");
        System.out.println("  TARGET_SERVER <ip_address> <port> - Set target server IP and control port.");
        System.out.println("  SET_ENCODING <TargetServerID> <JSON|XML> - Change data encoding for the targeted server.");
        System.out.println("  SET_FREQUENCY <TargetServerID> <milliseconds> - Change broadcast frequency for the targeted server.");
        System.out.println("  SET_UNIT <TargetServerID> <VariableName> <NewUnit> - Change unit for a variable on the targeted server.");
        System.out.println("  ACTIVATE_SERVER <TargetServerID> - Tell targeted server to start sending data.");
        System.out.println("  DEACTIVATE_SERVER <TargetServerID> - Tell targeted server to stop sending data.");
        System.out.println("  STOP_SERVER <TargetServerID> - Request the targeted server process to shut down.");
        System.out.println("  HELP - Show this help message.");
        System.out.println("  EXIT - Exit the client application.");
    }
}