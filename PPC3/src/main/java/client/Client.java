package client;

import common.*;
import gateway.EmailService;
import gateway.HttpGatewayServer;
import gateway.SmtpConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.util.Collections;
import java.util.HashMap; // Necesario para el nuevo método handleConsoleControlCommand
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Client {
	private static final int BROADCAST_PORT = 5000;
	private volatile boolean running = true;
	private final String logFileReceived = "client_received_broadcast.log";
	private final String logFileSent = "client_sent_control.log";
	private final String logFileServerResponses = "client_received_server_response.log";

	private final Map<String, DistributionMessage> latestServerData = new ConcurrentHashMap<>();

	// Target server para comandos enviados desde la consola del Broker
	private InetAddress currentConsoleTargetServerAddress = null;
	private int currentConsoleTargetServerPort = -1;

	private HttpGatewayServer httpGatewayServer;
	private static final int HTTP_GATEWAY_PORT = 8080;

	private EmailService emailService;

	// --- INICIO: Configuración de Servidores P2 Conocidos ---
	private static class ServerP2Info {
		String ip;
		int controlPort;

		public ServerP2Info(String ip, int controlPort) {
			this.ip = ip;
			this.controlPort = controlPort;
		}
	}

	// Mapa para almacenar la información de conexión de los servidores P2
	// conocidos.
	private final Map<String, ServerP2Info> knownP2Servers = Map.of("S1", new ServerP2Info("localhost", 5001), // Asume
																												// que
																												// S1
																												// está
																												// en
																												// localhost:5001
			"S2", new ServerP2Info("localhost", 5002), // Asume que S2 está en localhost:5002
			"S3", new ServerP2Info("localhost", 5003) // Asume que S3 está en localhost:5003
	);

	// Método para obtener la dirección y puerto de un servidor P2 por su ID
	private ServerP2Info getServerP2Details(String serverId) {
		if (serverId == null)
			return null;
		return knownP2Servers.get(serverId.toUpperCase()); // Búsqueda case-insensitive del ID
	}
	// --- FIN: Configuración de Servidores P2 Conocidos ---

	public static void main(String[] args) {
		new Client().start();
	}

	public void start() {
		System.out.println("Client (Broker) starting...");
		try {
			// currentConsoleTargetServerAddress se usa solo para la consola, no para la API
			// REST
			currentConsoleTargetServerAddress = InetAddress.getLocalHost();
		} catch (UnknownHostException e) {
			System.err.println("Could not resolve default localhost. Please set target server for console commands.");
		}

		new Thread(this::listenForBroadcasts, "BroadcastListenerThread").start();

		try {
			httpGatewayServer = new HttpGatewayServer(HTTP_GATEWAY_PORT, this);
			httpGatewayServer.start();
			// El mensaje de HttpGatewayServer.java es suficiente
		} catch (Exception e) {
			System.err.println("Could not start HTTPS Gateway Server: " + e.getMessage());
			e.printStackTrace();
		}

		SmtpConfig smtpConfig = new SmtpConfig("smtp.gmail.com", "587", "pjfe.ppc@gmail.com", // TU CORREO GMAIL
				"uftw umnt vjxe mhbb", // CONTRASEÑA DE APLICACIÓN
				true, "imap", "imap.gmail.com", "993", "pjfe.ppc@gmail.com", // TU CORREO GMAIL
				"uftw umnt vjxe mhbb", // CONTRASEÑA DE APLICACIÓN
				true, "pjfe.ppc@gmail.com", // Dirección "From" del servicio
				30);
		emailService = new EmailService(smtpConfig, this);
		emailService.start();
		System.out.println("Email Service initialized and started.");

		handleUserInput();

		System.out.println("Client shutting down...");
		running = false;
		if (httpGatewayServer != null) {
			httpGatewayServer.stop();
		}
		if (emailService != null) {
			emailService.stop();
		}
		System.out.println("Client shutdown complete.");
	}

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
					MessageUtils.logMessage(logFileReceived,
							"From " + packet.getAddress() + ":" + packet.getPort() + " - " + rawData);
					DistributionMessage distMsg = DistributionMessage.deserialize(rawData);
					if (distMsg != null) {
						latestServerData.put(distMsg.getServerId(), distMsg);
					}
				} catch (SocketTimeoutException e) {
					// Normal
				} catch (Exception e) {
					if (running) {
						System.err.println("Error receiving/parsing broadcast: " + e.getMessage());
					}
				}
			}
		} catch (SocketException e) {
			System.err.println("Broadcast listener socket error: " + e.getMessage());
		} finally {
			System.out.println("Broadcast listener stopped.");
		}
	}

	public Map<String, DistributionMessage> getLatestServerData() {
		return Collections.unmodifiableMap(latestServerData);
	}


	public ResponseMessage sendControlCommand(String targetServerIdInPayload, String command,
			Map<String, Object> parameters) {
		// Obtener IP y Puerto del Servidor P2 usando targetServerIdInPayload
		ServerP2Info serverDetails = getServerP2Details(targetServerIdInPayload);

		if (serverDetails == null) {
			System.err.println(
					"Broker: Server with ID '" + targetServerIdInPayload + "' not found in knownP2Servers map.");
			return new ResponseMessage("N/A", "ERROR",
					"Server with ID '" + targetServerIdInPayload + "' not found or not configured in the broker.");
		}

		InetAddress targetIp;
		try {
			targetIp = InetAddress.getByName(serverDetails.ip);
		} catch (UnknownHostException e) {
			System.err.println("Broker: Could not resolve IP for server " + targetServerIdInPayload + ": "
					+ serverDetails.ip + " - " + e.getMessage());
			return new ResponseMessage("N/A", "ERROR", "Could not resolve IP for server " + targetServerIdInPayload);
		}
		int targetPort = serverDetails.controlPort;

		// El targetServerIdInPayload es el que va en el cuerpo del mensaje de control.
		ControlMessage controlMsg = new ControlMessage(command, targetServerIdInPayload);
		if (parameters != null) {
			parameters.forEach(controlMsg::addParameter);
		}

		try (DatagramSocket unicastSocket = new DatagramSocket()) {
			unicastSocket.setSoTimeout(5000); // Timeout para la respuesta

			String serializedMsg = controlMsg.serialize();
			byte[] sendBuffer = serializedMsg.getBytes();

			// Usar targetIp y targetPort obtenidos del serverId para el envío UDP
			DatagramPacket sendPacket = new DatagramPacket(sendBuffer, sendBuffer.length, targetIp, targetPort);
			unicastSocket.send(sendPacket);
			MessageUtils.logMessage(logFileSent, "To " + targetIp + ":" + targetPort + " (for server "
					+ targetServerIdInPayload + ") - " + serializedMsg);
			System.out.println("Broker: Sent command '" + command + "' to P2 Server '" + targetServerIdInPayload
					+ "' at " + targetIp.getHostAddress() + ":" + targetPort);

			// Esperar respuesta
			byte[] receiveBuffer = new byte[1024];
			DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
			unicastSocket.receive(receivePacket);
			String responseData = new String(receivePacket.getData(), 0, receivePacket.getLength());
			MessageUtils.logMessage(logFileServerResponses,
					"From " + receivePacket.getAddress() + ":" + receivePacket.getPort() + " - " + responseData);
			return ResponseMessage.deserialize(responseData);

		} catch (SocketTimeoutException e) {
			System.err.println("No response from P2 server " + targetServerIdInPayload + " ("
					+ targetIp.getHostAddress() + ":" + targetPort + ") (timeout).");
			return new ResponseMessage(controlMsg.getMessageId(), "TIMEOUT",
					"No response from server " + targetServerIdInPayload);
		} catch (IOException e) {
			System.err.println("Client unicast socket error for P2 server " + targetServerIdInPayload + " ("
					+ targetIp.getHostAddress() + ":" + targetPort + "): " + e.getMessage());
			e.printStackTrace();
			return new ResponseMessage(controlMsg.getMessageId(), "ERROR",
					"Client socket error while communicating with " + targetServerIdInPayload);
		}
	}

	private void handleUserInput() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
			System.out.println("Enter console commands. Type 'HELP' for options, 'EXIT' to quit Broker.");
			if (currentConsoleTargetServerAddress == null) { // Usar currentConsoleTarget...
				System.out.println("WARN: Target server for console commands not set. Use TARGET_SERVER <ip> <port>");
			} else {
				System.out.println("Default console target: " + currentConsoleTargetServerAddress.getHostAddress()
						+ (currentConsoleTargetServerPort > 0 ? ":" + currentConsoleTargetServerPort
								: " (port not set)"));
			}

			while (running) {
				System.out.print("\nClient Console > ");
				String inputLine = reader.readLine();
				if (inputLine == null || inputLine.trim().equalsIgnoreCase("EXIT")) {
					running = false;
					break;
				}

				String[] parts = inputLine.trim().split("\\s+");
				if (parts.length == 0 || parts[0].isEmpty())
					continue;

				String commandTypeFromConsole = parts[0].toUpperCase();

				// El comando TARGET_SERVER es especial para la consola
				if ("TARGET_SERVER".equals(commandTypeFromConsole)) {
					if (parts.length < 3) {
						System.out.println("Usage: TARGET_SERVER <ip_address> <port>");
						continue;
					}
					try {
						currentConsoleTargetServerAddress = InetAddress.getByName(parts[1]);
						currentConsoleTargetServerPort = Integer.parseInt(parts[2]);
						System.out.println("Console target server (for console commands only) set to "
								+ currentConsoleTargetServerAddress.getHostAddress() + ":"
								+ currentConsoleTargetServerPort);
					} catch (UnknownHostException e) {
						System.err.println("Invalid server address for console target: " + parts[1]);
						currentConsoleTargetServerAddress = null;
						currentConsoleTargetServerPort = -1;
					} catch (NumberFormatException e) {
						System.err.println("Invalid port number for console target: " + parts[2]);
						currentConsoleTargetServerPort = -1;
					}
					continue;
				}

				
				handleConsoleControlCommand(commandTypeFromConsole, parts);
			}
		} catch (IOException e) {
			System.err.println("Client user input error: " + e.getMessage());
			e.printStackTrace();
		} finally {
			System.out.println("User input handler stopped.");
		}
	}

	// Método para manejar comandos de control originados desde la consola del
	// Broker
	private void handleConsoleControlCommand(String commandType, String[] parts) {
		Map<String, Object> parameters = new HashMap<>();
		String targetServerIdInPayload = null; // El ID del servidor que va en el payload del mensaje
		String actualCommandToSend = commandType; // Por defecto, el comando es el mismo

		if (currentConsoleTargetServerAddress == null || currentConsoleTargetServerPort == -1
				&& !("LIST_DATA".equals(commandType) || "HELP".equals(commandType))) { // LIST_DATA y HELP no necesitan
																						// target
			System.err.println(
					"Error: Console target server IP or port not set. Use TARGET_SERVER <ip_address> <port> first for this console command.");
		}

		switch (commandType) {
		case "HELP":
			showHelp();
			return; // No envía mensaje
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
			return; // No envía mensaje

		// Comandos que SÍ envían un mensaje
		case "SET_ENCODING":
			if (parts.length < 3) {
				System.out.println("Usage: SET_ENCODING <TargetServerID_in_payload> <JSON|XML>");
				return;
			}
			targetServerIdInPayload = parts[1];
			parameters.put("encoding", parts[2].toUpperCase());
			break;
		case "SET_FREQUENCY":
			if (parts.length < 3) {
				System.out.println("Usage: SET_FREQUENCY <TargetServerID_in_payload> <frequency_ms>");
				return;
			}
			targetServerIdInPayload = parts[1];
			try {
				parameters.put("frequency", Integer.parseInt(parts[2]));
			} catch (NumberFormatException e) {
				System.err.println("Invalid frequency.");
				return;
			}
			break;
		case "SET_UNIT":
			if (parts.length < 4) {
				System.out.println("Usage: SET_UNIT <TargetServerID_in_payload> <VarName> <NewUnit>");
				return;
			}
			targetServerIdInPayload = parts[1];
			actualCommandToSend = "SET_VARIABLE_UNIT"; // El servidor P2 espera este nombre de comando
			parameters.put("variableName", parts[2]);
			parameters.put("newUnit", parts[3]);
			break;
		case "ACTIVATE_SERVER":
			if (parts.length < 2) {
				System.out.println("Usage: ACTIVATE_SERVER <TargetServerID_in_payload>");
				return;
			}
			targetServerIdInPayload = parts[1];
			actualCommandToSend = "TOGGLE_SENDING_DATA";
			parameters.put("active", true);
			break;
		case "DEACTIVATE_SERVER":
			if (parts.length < 2) {
				System.out.println("Usage: DEACTIVATE_SERVER <TargetServerID_in_payload>");
				return;
			}
			targetServerIdInPayload = parts[1];
			actualCommandToSend = "TOGGLE_SENDING_DATA";
			parameters.put("active", false);
			break;
		case "STOP_SERVER":
			if (parts.length < 2) {
				System.out.println("Usage: STOP_SERVER <TargetServerID_in_payload>");
				return;
			}
			targetServerIdInPayload = parts[1];
			actualCommandToSend = "STOP_SERVER_PROCESS";
			break;
		default:
			System.out.println("Unknown command entered in console. Type 'HELP'.");
			return; // No envía mensaje si el comando no se reconoce aquí
		}

		if (targetServerIdInPayload == null) {
			// Esto no debería ocurrir si la lógica del switch está bien para los comandos
			// que envían mensajes.
			System.err.println(
					"Cannot send command: TargetServerID for payload is missing for console command: " + commandType);
			return;
		}

		// Ahora llamamos al método sendControlCommand refactorizado
		System.out.println("Console: Sending command '" + actualCommandToSend + "' for P2 Server ID '"
				+ targetServerIdInPayload + "'...");
		ResponseMessage responseMsg = sendControlCommand(targetServerIdInPayload, actualCommandToSend, parameters);

		System.out.println("P2 Server Response (from console command):");
		if (responseMsg != null) {
			System.out.println("  Status: " + responseMsg.getStatus());
			System.out.println("  Details: " + responseMsg.getDetails());
			if ("ERROR".equals(responseMsg.getStatus()) && "TIMEOUT".equals(responseMsg.getDetails())) {
				System.err.println("  (No response from server " + targetServerIdInPayload + ")");
			}
		} else {
			// Esto podría ocurrir si sendControlCommand devuelve null debido a un error
			// antes del envío
			System.out.println("  No valid response object received or error determining target.");
		}
	}

	private void showHelp() {
		System.out.println("Available console commands:");
		System.out.println(
				"  TARGET_SERVER <ip_address> <port> - Set console's target server for sending control messages via console.");
		System.out.println(
				"                                      (Note: API REST determines target from 'serverId' URL parameter).");
		System.out.println("  LIST_DATA - Display current data stored by the broker.");
		System.out.println(
				"  SET_ENCODING <TargetServerID_in_payload> <JSON|XML> - Change data encoding for the specified P2 server.");
		System.out.println(
				"  SET_FREQUENCY <TargetServerID_in_payload> <milliseconds> - Change broadcast frequency for the specified P2 server.");
		System.out.println(
				"  SET_UNIT <TargetServerID_in_payload> <VariableName> <NewUnit> - Change unit for a variable on the specified P2 server.");
		System.out.println(
				"  ACTIVATE_SERVER <TargetServerID_in_payload> - Tell specified P2 server to start sending data.");
		System.out.println(
				"  DEACTIVATE_SERVER <TargetServerID_in_payload> - Tell specified P2 server to stop sending data.");
		System.out.println(
				"  STOP_SERVER <TargetServerID_in_payload> - Request the specified P2 server process to shut down.");
		System.out.println("  HELP - Show this help message.");
		System.out.println("  EXIT - Exit the client/broker application.");
		System.out.println("\nHTTPS Gateway available at https://localhost:" + HTTP_GATEWAY_PORT + "/");
		System.out.println("  - HTML Weather: https://localhost:" + HTTP_GATEWAY_PORT + "/meteorologia.html");
		System.out
				.println("  - REST API (example): https://localhost:" + HTTP_GATEWAY_PORT + "/apirest/muestra_valores");
		System.out.println("  - Email Service: Check your configured email inbox ("
				+ (emailService != null ? emailService.getServiceEmailAddress() : "not configured")
				+ ") for requests/responses.");
	}
}
