package server;

import common.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Server {
    private final String serverId;
    private static final int BROADCAST_PORT = 5000;
    private final int controlPort; // Puerto de control específico para esta instancia
    private static final String BROADCAST_ADDRESS = "255.255.255.255";

    private volatile boolean running = true;
    private volatile String currentEncoding = MessageUtils.ENCODING_JSON;
    private volatile int broadcastFrequencyMs = 1000;
    private volatile boolean isSendingData = true;

    private final List<WeatherVariable> managedVariables = new ArrayList<>();
    private final Random random = new Random();
    private ScheduledExecutorService broadcastScheduler;
    private final String logFileSent;
    private final String logFileReceived;

    public Server(String serverId, int controlPort, List<WeatherVariable> initialVariables) {
        this.serverId = serverId;
        this.controlPort = controlPort; // Asignar el puerto de control
        this.managedVariables.addAll(initialVariables);
        this.logFileSent = "server_" + serverId + "_sent_broadcast.log";
        this.logFileReceived = "server_" + serverId + "_received_control.log";
    }

    public static void main(String[] args) {
        if (args.length < 2) { // Necesitamos al menos serverId y controlPort
            System.err.println("Usage: java servidor.Server <serverId> <controlPort> [variable1Name variable1Unit] ...");
            System.err.println("Example: java servidor.Server S1 5001 temperature C humidity % pressure hPa");
            return;
        }
        String id = args[0];
        String port = args[1];
        int specificControlPort = Integer.parseInt(port);
        List<WeatherVariable> vars = new ArrayList<>();
        if (args.length > 2 && (args.length - 2) % 2 == 0) {
            for (int i = 2; i < args.length; i += 2) {
                vars.add(new WeatherVariable(args[i], 0, args[i + 1]));
            }
        } else if (args.length == 2) { // Solo serverId y controlPort, usar variables por defecto
            vars.add(new WeatherVariable("temperature", 0, "C"));
            vars.add(new WeatherVariable("humidity", 0, "%"));
            vars.add(new WeatherVariable("pressure", 0, "hPa"));
        } else if (args.length > 2) { // Número incorrecto de argumentos de variables
             System.err.println("Incorrect number of variable arguments. Each variable needs a name and a unit.");
             return;
        }


        if (vars.isEmpty()) { // Si no se proporcionaron variables y no se usaron las de por defecto
             System.err.println("Server must manage at least one variable, or provide default if none specified.");
             // Añadir algunas por defecto si es necesario, o salir
             vars.add(new WeatherVariable("defaultVar1", 0, "unitA"));
             vars.add(new WeatherVariable("defaultVar2", 0, "unitB"));
             vars.add(new WeatherVariable("defaultVar3", 0, "unitC"));
        }
         if (vars.size() < 3 && args.length > 2) { // Si se proporcionaron vars pero menos de 3 (opcional, ajustar a requerimiento)
            System.out.println("Warning: Server " + id + " is managing less than 3 user-defined variables.");
        }


        new Server(id, specificControlPort, vars).start();
    }

    public void start() {
        System.out.println("Server " + serverId + " starting...");
        System.out.println("Managing variables: " + managedVariables);

        new Thread(this::listenForControlMessages).start(); // Usa this.controlPort

        broadcastScheduler = Executors.newSingleThreadScheduledExecutor();
        scheduleBroadcast();

        System.out.println("Server " + serverId + " started. Broadcasting on port " + BROADCAST_PORT +
                           ", Listening for control on port " + this.controlPort); // Usar el puerto de instancia
    }

    private void scheduleBroadcast() { // Sin cambios
        if (broadcastScheduler.isShutdown() || broadcastScheduler.isTerminated()) {
             broadcastScheduler = Executors.newSingleThreadScheduledExecutor();
        }
        broadcastScheduler.scheduleAtFixedRate(() -> {
            if (running && isSendingData) {
                broadcastData();
            }
        }, 0, broadcastFrequencyMs, TimeUnit.MILLISECONDS);
    }

    private void broadcastData() { // Sin cambios, excepto logging
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress broadcastAddr = InetAddress.getByName(BROADCAST_ADDRESS);
            DistributionMessage distMsg = new DistributionMessage(serverId, currentEncoding);
            // ... (generación de variables igual que antes)
            for (WeatherVariable templateVar : managedVariables) {
                double value = 0;
                if (templateVar.getName().toLowerCase().contains("temperature")) value = 10 + random.nextDouble() * 20;
                else if (templateVar.getName().toLowerCase().contains("humidity")) value = 30 + random.nextDouble() * 60;
                else if (templateVar.getName().toLowerCase().contains("pressure")) value = 980 + random.nextDouble() * 50;
                else value = random.nextDouble() * 100;
                distMsg.addVariable(new WeatherVariable(templateVar.getName(), value, templateVar.getUnit()));
            }

            String serializedMessage = distMsg.serialize();
            byte[] buffer = serializedMessage.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, broadcastAddr, BROADCAST_PORT);
            socket.send(packet);
            MessageUtils.logMessage(logFileSent, serializedMessage);
        } catch (Exception e) {
            System.err.println(serverId + " Error broadcasting data: " + e.getMessage());
        }
    }

    private void listenForControlMessages() {
        try (DatagramSocket socket = new DatagramSocket(this.controlPort)) { // Usa el puerto de control de la instancia
            byte[] buffer = new byte[2048];
            System.out.println(serverId + " listening for control messages on port " + this.controlPort);
            while (running) {
                // ... (resto igual que antes)
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String receivedData = new String(packet.getData(), 0, packet.getLength());
                MessageUtils.logMessage(logFileReceived, "From " + packet.getAddress() + ":" + packet.getPort() + " - " + receivedData);
                System.out.println(serverId + " received control: " + receivedData + " from " + packet.getAddress().getHostAddress() + ":" + packet.getPort());

                ControlMessage controlMsg = null;
                try {
                     controlMsg = ControlMessage.deserialize(receivedData);
                } catch (Exception e) {
                    System.err.println(serverId + " Error parsing control message: " + e.getMessage());
                    sendResponse(socket, packet.getAddress(), packet.getPort(), "N/A", "ERROR_PARSING", "Failed to parse control message");
                    continue;
                }

                if (controlMsg != null && (serverId.equals(controlMsg.getTargetServerId()) || "ALL".equalsIgnoreCase(controlMsg.getTargetServerId()))) {
                     processControlMessage(controlMsg, packet.getAddress(), packet.getPort(), socket);
                } else if (controlMsg != null) {
                    System.out.println(serverId + ": Ignored control message for target " + controlMsg.getTargetServerId());
                } else {
                    System.out.println(serverId + ": Received null control message object after deserialization.");
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println(serverId + " Control Message listener error: " + e.getMessage());
                e.printStackTrace(); // Imprime el stack trace para más detalles
            }
        } finally {
            System.out.println(serverId + " Control message listener stopped.");
        }
    }
    // processControlMessage y sendResponse sin cambios
private void processControlMessage(ControlMessage msg, InetAddress clientAddr, int clientPort, DatagramSocket socket) {
    String command = msg.getCommand();
    Map<String, Object> params = msg.getParameters();
    String responseDetails = "Command '" + command + "' executed.";
    String status = "OK";

    System.out.println(serverId + " processing command: " + command + " with params: " + params);

    try {
        switch (command.toUpperCase()) {
            case "SET_ENCODING":
                String newEncoding = (String) params.get("encoding");
                if (MessageUtils.ENCODING_JSON.equalsIgnoreCase(newEncoding) || MessageUtils.ENCODING_XML.equalsIgnoreCase(newEncoding)) {
                    this.currentEncoding = newEncoding.toUpperCase();
                    responseDetails = "Encoding set to " + this.currentEncoding;
                } else {
                    status = "ERROR";
                    responseDetails = "Invalid encoding: " + newEncoding;
                }
                break;
            case "SET_FREQUENCY":
                Object freqObj = params.get("frequency");
                int newFreq;
                if (freqObj instanceof Number) {
                    newFreq = ((Number) freqObj).intValue();
                } else if (freqObj instanceof String) {
                    newFreq = Integer.parseInt((String) freqObj);
                } else {
                    status = "ERROR";
                    responseDetails = "Invalid frequency type: " + (freqObj != null ? freqObj.getClass().getName() : "null");
                    sendResponse(socket, clientAddr, clientPort, msg.getMessageId(), status, responseDetails);
                    return; 
                }

                if (newFreq > 0) {
                    this.broadcastFrequencyMs = newFreq;
                    if (broadcastScheduler != null && !broadcastScheduler.isShutdown()) {
                        broadcastScheduler.shutdownNow(); 
                    }
                    scheduleBroadcast();
                    responseDetails = "Broadcast frequency set to " + this.broadcastFrequencyMs + "ms";
                } else {
                    status = "ERROR";
                    responseDetails = "Invalid frequency: " + newFreq;
                }
                break;
            case "SET_UNIT":
                String varName = (String) params.get("variableName");
                String newUnit = (String) params.get("newUnit");
                boolean found = false;
                for(WeatherVariable wv : managedVariables) {
                    if(wv.getName().equalsIgnoreCase(varName)) {
                        wv.setUnit(newUnit);
                        found = true;
                        responseDetails = "Unit for " + varName + " changed to " + newUnit;
                        break;
                    }
                }
                if (!found) {
                    status = "ERROR";
                    responseDetails = "Variable " + varName + " not managed by this server.";
                }
                break;
            case "TOGGLE_SENDING_DATA":
                 Object activeObj = params.get("active"); 
                 if (activeObj instanceof Boolean) {
                    this.isSendingData = (Boolean) activeObj;
                     responseDetails = "Data sending " + (this.isSendingData ? "activated" : "deactivated");
                 } else {
                    status = "ERROR";
                    responseDetails = "Invalid type for 'active' parameter: " + (activeObj != null ? activeObj.getClass().getName() : "null");
                 }
                break;
            case "STOP_SERVER_PROCESS": 
                responseDetails = "Server " + serverId + " stopping.";
                this.running = false;
                this.isSendingData = false;
                if (broadcastScheduler != null) broadcastScheduler.shutdownNow();
                break;
            default:
                status = "ERROR";
                responseDetails = "Unknown command: " + command;
        }
    } catch (Exception e) {
        status = "ERROR";
        responseDetails = "Error processing command '" + command + "': " + e.getMessage();
        System.err.println(serverId + " " + responseDetails);
        e.printStackTrace();
    }

    sendResponse(socket, clientAddr, clientPort, msg.getMessageId(), status, responseDetails);
    if ("STOP_SERVER_PROCESS".equalsIgnoreCase(command) && "OK".equals(status)) {
        // Da tiempo a enviar la respuesta antes de cerrar el socket del listener
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                 if (socket != null && !socket.isClosed()) socket.close(); // Cierra el socket del listener
            }
        }, 500); // Espera 500ms
    }
}
private void sendResponse(DatagramSocket socket, InetAddress clientAddr, int clientPort, String originalMsgId, String status, String details) {
    ResponseMessage response = new ResponseMessage(originalMsgId, status, details);
    String serializedResponse = response.serialize(); 
    byte[] buffer = serializedResponse.getBytes();
    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length, clientAddr, clientPort);
    try {
        socket.send(responsePacket);
        System.out.println(serverId + " sent response to " + clientAddr.getHostAddress() + ":" + clientPort + " -> " + serializedResponse);
    } catch (IOException e) {
        System.err.println(serverId + " Error sending response: " + e.getMessage());
    }
}
}