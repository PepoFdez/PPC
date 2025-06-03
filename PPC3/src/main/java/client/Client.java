package client;

import common.*;
import gateway.EmailService;
import gateway.HttpGatewayServer;
import gateway.SmtpConfig; // Asegúrate que SmtpConfig esté bien definido

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Client {
    private static final int BROADCAST_PORT = 5000;
    private volatile boolean running = true;
    private final String logFileReceived = "client_received_broadcast.log";
    private final String logFileSent = "client_sent_control.log";
    private final String logFileServerResponses = "client_received_server_response.log";

    private final Map<String, DistributionMessage> latestServerData = new ConcurrentHashMap<>();
    private InetAddress currentTargetServerAddress = null;
    private int currentTargetServerPort = -1;

    private HttpGatewayServer httpGatewayServer;
    private static final int HTTP_GATEWAY_PORT = 8080;

    private EmailService emailService;

    public static void main(String[] args) {
        new Client().start();
    }

    public void start() {
        System.out.println("Client (Broker) starting...");
        try {
            currentTargetServerAddress = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            System.err.println("Could not resolve default localhost. Please set target server for console commands.");
        }

        new Thread(this::listenForBroadcasts, "BroadcastListenerThread").start();

        try {
            httpGatewayServer = new HttpGatewayServer(HTTP_GATEWAY_PORT, this);
            httpGatewayServer.start();
            System.out.println("HTTPS Gateway (antes HTTP/REST) started on port " + HTTP_GATEWAY_PORT); // Etiqueta actualizada
        } catch (Exception e) { // HttpGatewayServer constructor ahora puede lanzar Exception
            System.err.println("Could not start HTTPS Gateway Server: " + e.getMessage());
            e.printStackTrace();
        }

        // Asegúrate de que SmtpConfig esté correctamente definido y que los valores sean para tu servidor de correo
        SmtpConfig smtpConfig = new SmtpConfig(
                "smtp.gmail.com", // Ejemplo: smtp.gmail.com
                "587",            // Puerto SMTP (587 para TLS, 465 para SSL)
                "pjfe.ppc@gmail.com", // Tu dirección de correo
                "uftw umnt vjxe mhbb", // Tu contraseña o contraseña de aplicación
                true,             // useTlsSmtp (true para puerto 587)
                "imap",           // storeProtocol ("imap" o "pop3")
                "imap.gmail.com", // Servidor IMAP/POP3
                "993",            // Puerto IMAP (993 para IMAPS) o POP3 (995 para POP3S)
                "pjfe.ppc@gmail.com", // Usuario para recibir
                "uftw umnt vjxe mhbb", // Contraseña para recibir
                true,             // useSslStore (true para IMAPS/POP3S)
                "pjfe.ppc@gmail.com", // Dirección desde la que el servicio envía/recibe
                30                // polling interval in seconds
        );
        emailService = new EmailService(smtpConfig, this);
        emailService.start(); // Asegúrate de descomentar esto para que el servicio de correo inicie el sondeo
        System.out.println("Email Service initialized and started.");

        handleUserInput();

        System.out.println("Client shutting down...");
        running = false;
        if (httpGatewayServer != null) {
            httpGatewayServer.stop();
        }
        if (emailService != null) {
            emailService.stop(); // Asegúrate de tener un método stop en EmailService
        }
        System.out.println("Client shutdown complete.");
    }

    // ... listenForBroadcasts() sin cambios ...
    private void listenForBroadcasts() {
        try (DatagramSocket broadcastSocket = new DatagramSocket(BROADCAST_PORT)) {
            broadcastSocket.setSoTimeout(5000);
            byte[] buffer = new byte[8192];

            System.out.println("Client listening for broadcasts on port " + BROADCAST_PORT);
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    broadcastSocket.receive(packet);
                    String rawData = new String(packet.getData(), 0, packet.getLength());

                    MessageUtils.logMessage(logFileReceived, "From " + packet.getAddress() + ":" + packet.getPort() + " - " + rawData);
                    DistributionMessage distMsg = DistributionMessage.deserialize(rawData);

                    if (distMsg != null) {
                        latestServerData.put(distMsg.getServerId(), distMsg);
                        // System.out.println("Broker: Updated data for server " + distMsg.getServerId()); // Opcional para menos verbosidad
                    } else {
                        // System.err.println("Failed to parse broadcast message from " + packet.getAddress());
                    }
                } catch (SocketTimeoutException e) {
                    // Normal timeout
                } catch (Exception e) {
                    if (running) {
                        System.err.println("Error receiving/parsing broadcast: " + e.getMessage());
                        // e.printStackTrace(); // Descomentar para debug detallado
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Broadcast listener socket error: " + e.getMessage());
        } finally {
            System.out.println("Broadcast listener stopped.");
        }
    }


    // ... getLatestServerData() sin cambios ...
    public Map<String, DistributionMessage> getLatestServerData() {
        return Collections.unmodifiableMap(latestServerData);
    }

    // ... sendControlCommand() sin cambios ...
    public ResponseMessage sendControlCommand(String targetServerId, String command, Map<String, Object> parameters) {
        if (currentTargetServerAddress == null || currentTargetServerPort == -1) {
             return new ResponseMessage("N/A", "ERROR", "Client's target server for control messages not set via console's TARGET_SERVER command.");
        }

        ControlMessage controlMsg = new ControlMessage(command, targetServerId);
        if (parameters != null) {
            parameters.forEach(controlMsg::addParameter);
        }

        try (DatagramSocket unicastSocket = new DatagramSocket()) {
            unicastSocket.setSoTimeout(5000);

            String serializedMsg = controlMsg.serialize();
            byte[] sendBuffer = serializedMsg.getBytes();
            DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, currentTargetServerAddress, currentTargetServerPort);
            unicastSocket.send(sendPacket);
            MessageUtils.logMessage(logFileSent, "To " + currentTargetServerAddress + ":" + currentTargetServerPort + " (via Gateway for " + targetServerId + ") - " + serializedMsg);
            // System.out.println("Gateway: Sent to " + currentTargetServerAddress.getHostAddress() + ":" + currentTargetServerPort + " -> " + serializedMsg);

            byte[] receiveBuffer = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            unicastSocket.receive(receivePacket);
            String responseData = new String(receivePacket.getData(), 0, receivePacket.getLength());
            MessageUtils.logMessage(logFileServerResponses, "From " + receivePacket.getAddress() + ":" + receivePacket.getPort() + " - " + responseData);
            return ResponseMessage.deserialize(responseData);

        } catch (SocketTimeoutException e) {
            System.err.println("No response from server " + targetServerId + " (timeout via Gateway).");
            return new ResponseMessage(controlMsg.getMessageId(), "TIMEOUT", "No response from server.");
        } catch (IOException e) {
            System.err.println("Client unicast socket error (via Gateway for " + targetServerId + "): " + e.getMessage());
            e.printStackTrace();
            return new ResponseMessage(controlMsg.getMessageId(), "ERROR", "Client socket error: " + e.getMessage());
        }
    }

    // ... handleUserInput() sin cambios ...
    private void handleUserInput() {
        try (DatagramSocket unicastSocket = new DatagramSocket();
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {

            unicastSocket.setSoTimeout(5000);

            System.out.println("Enter console commands. Type 'HELP' for options, 'EXIT' to quit Broker.");
            if(currentTargetServerAddress == null) {
                System.out.println("WARN: Target server for console commands not set. Use TARGET_SERVER <ip> <port>");
            } else {
                 System.out.println("Default console target: " + currentTargetServerAddress.getHostAddress() + (currentTargetServerPort > 0 ? ":" + currentTargetServerPort : " (port not set)"));
            }

            while (running) {
                System.out.print("\nClient Console > ");
                String inputLine = reader.readLine();
                if (inputLine == null || inputLine.trim().equalsIgnoreCase("EXIT")) {
                    running = false;
                    break;
                }

                String[] parts = inputLine.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty()) continue;

                String commandType = parts[0].toUpperCase();
                ControlMessage controlMsg = null;

                boolean requiresTarget = !"HELP".equals(commandType) && !"TARGET_SERVER".equals(commandType) && !"LIST_DATA".equals(commandType);
                if (requiresTarget && (currentTargetServerAddress == null || currentTargetServerPort == -1)) {
                    System.err.println("Error: Target server IP or port not set for console. Use TARGET_SERVER <ip_address> <port> first.");
                    continue;
                }

                switch (commandType) {
                    case "HELP":
                        showHelp();
                        continue;
                    case "LIST_DATA":
                        System.out.println("--- Current Brokered Data ---");
                        if (latestServerData.isEmpty()) {
                            System.out.println("No data received from servers yet.");
                        } else {
                            latestServerData.forEach((serverId, msg) -> {
                                System.out.println("\nServer ID: " + serverId);
                                System.out.println("  Timestamp: " + new java.util.Date(msg.getTimestamp()));
                                System.out.println("  Encoding: " + msg.getEncodingFormat());
                                for (WeatherVariable var : msg.getVariables()) {
                                    System.out.println("    - " + var.toString());
                                }
                            });
                        }
                        System.out.println("--- End Brokered Data ---");
                        continue;

                    case "TARGET_SERVER":
                        if (parts.length < 3) {
                             System.out.println("Usage: TARGET_SERVER <ip_address> <port>");
                             continue;
                        }
                        try {
                            currentTargetServerAddress = InetAddress.getByName(parts[1]);
                            currentTargetServerPort = Integer.parseInt(parts[2]);
                            System.out.println("Console target server set to " + currentTargetServerAddress.getHostAddress() + ":" + currentTargetServerPort);
                        } catch (UnknownHostException e) {
                            System.err.println("Invalid server address: " + parts[1]);
                            currentTargetServerAddress = null; currentTargetServerPort = -1;
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid port number: " + parts[2]);
                             currentTargetServerPort = -1;
                        }
                        continue;
                    case "SET_ENCODING":
                         if (parts.length < 3) { System.out.println("Usage: SET_ENCODING <TargetServerID_for_msg_payload> <JSON|XML>"); continue; }
                        controlMsg = new ControlMessage(commandType, parts[1]);
                        controlMsg.addParameter("encoding", parts[2].toUpperCase());
                        break;
                    case "SET_FREQUENCY":
                        if (parts.length < 3) { System.out.println("Usage: SET_FREQUENCY <TargetServerID_for_msg_payload> <frequency_ms>"); continue; }
                        try {
                            controlMsg = new ControlMessage(commandType, parts[1]);
                            controlMsg.addParameter("frequency", Integer.parseInt(parts[2]));
                        } catch (NumberFormatException e) { System.err.println("Invalid frequency."); continue; }
                        break;
                    case "SET_UNIT":
                        if (parts.length < 4) { System.out.println("Usage: SET_UNIT <TargetServerID_for_msg_payload> <VarName> <NewUnit>"); continue; }
                        controlMsg = new ControlMessage("SET_VARIABLE_UNIT", parts[1]);
                        controlMsg.addParameter("variableName", parts[2]);
                        controlMsg.addParameter("newUnit", parts[3]);
                        break;
                    case "ACTIVATE_SERVER":
                         if (parts.length < 2) { System.out.println("Usage: ACTIVATE_SERVER <TargetServerID_for_msg_payload>"); continue; }
                        controlMsg = new ControlMessage("TOGGLE_SENDING_DATA", parts[1]);
                        controlMsg.addParameter("active", true);
                        break;
                    case "DEACTIVATE_SERVER":
                        if (parts.length < 2) { System.out.println("Usage: DEACTIVATE_SERVER <TargetServerID_for_msg_payload>"); continue; }
                        controlMsg = new ControlMessage("TOGGLE_SENDING_DATA", parts[1]);
                        controlMsg.addParameter("active", false);
                        break;
                    case "STOP_SERVER":
                        if (parts.length < 2) { System.out.println("Usage: STOP_SERVER <TargetServerID_for_msg_payload>"); continue; }
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
                        if (responseMsg != null) {
                            System.out.println("  Status: " + responseMsg.getStatus());
                            System.out.println("  Details: " + responseMsg.getDetails());
                        } else {
                            System.out.println("  Failed to parse server response: " + responseData);
                        }
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

    // ... showHelp() sin cambios ...
    private void showHelp() {
        System.out.println("Available console commands:");
        System.out.println("  TARGET_SERVER <ip_address> <port> - Set target server for console control messages.");
        System.out.println("  LIST_DATA - Display current data stored by the broker.");
        System.out.println("  SET_ENCODING <TargetServerID_in_payload> <JSON|XML> - Change data encoding for the targeted server.");
        System.out.println("  SET_FREQUENCY <TargetServerID_in_payload> <milliseconds> - Change broadcast frequency.");
        System.out.println("  SET_UNIT <TargetServerID_in_payload> <VariableName> <NewUnit> - Change unit for a variable.");
        System.out.println("  ACTIVATE_SERVER <TargetServerID_in_payload> - Tell server to start sending data.");
        System.out.println("  DEACTIVATE_SERVER <TargetServerID_in_payload> - Tell server to stop sending data.");
        System.out.println("  STOP_SERVER <TargetServerID_in_payload> - Request server process to shut down.");
        System.out.println("  HELP - Show this help message.");
        System.out.println("  EXIT - Exit the client/broker application.");
        System.out.println("\nHTTPS Gateway available at https://localhost:" + HTTP_GATEWAY_PORT + "/"); // Actualizado a HTTPS
        System.out.println("  - HTML Weather: https://localhost:" + HTTP_GATEWAY_PORT + "/meteorologia.html");
        System.out.println("  - REST API (example): https://localhost:" + HTTP_GATEWAY_PORT + "/apirest/muestra_valores");
    }
}