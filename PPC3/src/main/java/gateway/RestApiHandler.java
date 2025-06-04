package gateway;

import client.Client;
import common.DistributionMessage;
import common.MessageUtils; // For toJson
import common.ResponseMessage; // For responses from control commands
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.net.URI;

public class RestApiHandler implements HttpHandler {
    private final Client clientBroker;

    public RestApiHandler(Client clientBroker) {
        this.clientBroker = clientBroker;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        URI requestUri = exchange.getRequestURI();
        String path = requestUri.getPath();
        String method = exchange.getRequestMethod(); // [cite: 62]

        // Basic routing
        if ("/apirest/muestra_valores".equals(path)) {
            if ("GET".equalsIgnoreCase(method)) {
                handleMuestraValores(exchange);
            } else {
                sendJsonResponse(exchange, 405, MessageUtils.toJson(new ErrorResponse("Method Not Allowed")));
            }
        } else if (path.startsWith("/apirest/cambia_parametro")) {
             if ("GET".equalsIgnoreCase(method)) { // As per example[cite: 51], though POST/PUT is better [cite: 62]
                handleCambiaParametro(exchange);
            } else {
                sendJsonResponse(exchange, 405, MessageUtils.toJson(new ErrorResponse("Method Not Allowed for this endpoint. Use GET as per example or consider POST/PUT.")));
            }
        }
        // Add more specific endpoints if needed for different REST methods on /apirest/servers/{serverId}/param
        else {
            sendJsonResponse(exchange, 404, MessageUtils.toJson(new ErrorResponse("Not Found")));
        }
    }

    private void handleMuestraValores(HttpExchange exchange) throws IOException {
        Map<String, DistributionMessage> data = clientBroker.getLatestServerData();
        String jsonResponse = MessageUtils.toJson(data); // Use GSON from MessageUtils
        sendJsonResponse(exchange, 200, jsonResponse);
    }

    private void handleCambiaParametro(HttpExchange exchange) throws IOException {
        // Example: /apirest/cambia_parametro?serverId=S1&param=frequency&value=2000
        // Or: /apirest/cambia_parametro?serverId=S1&command=SET_ENCODING&encoding=XML
        Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());

        String serverId = params.get("serverId");
        String commandName = params.get("command"); // e.g., SET_FREQUENCY, SET_ENCODING
        Map<String, Object> controlParams = new HashMap<>();

        if (serverId == null || serverId.isEmpty()) {
            sendJsonResponse(exchange, 400, MessageUtils.toJson(new ErrorResponse("Missing 'serverId' parameter")));
            return;
        }
        
        // This is a simplified direct mapping. A more robust solution would validate commandName and parameters.
        // The example URL was /apirest/cambia_parametro?freq=2 [cite: 51]
        // Let's adapt to a more generic structure:
        // /apirest/cambia_parametro?serverId=S1&command=SET_FREQUENCY&frequency=2000
        // /apirest/cambia_parametro?serverId=S1&command=SET_ENCODING&encoding=XML
        // /apirest/cambia_parametro?serverId=S1&command=TOGGLE_SENDING_DATA&active=false
        // /apirest/cambia_parametro?serverId=S1&command=SET_VARIABLE_UNIT&variableName=temp&newUnit=K

        if (commandName == null || commandName.isEmpty()) {
            // Fallback to old example if 'command' is not present but 'param' and 'value' are
            // This is for the freq=2 example in the PDF, which is a bit ambiguous
            String param = params.get("param");
            String value = params.get("value");
            if (param != null && value != null) {
                if ("frequency".equalsIgnoreCase(param)) { // Compatibility with ?freq=X example [cite: 51]
                    commandName = "SET_FREQUENCY";
                     try {
                        controlParams.put("frequency", Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        sendJsonResponse(exchange, 400, MessageUtils.toJson(new ErrorResponse("Invalid value for frequency.")));
                        return;
                    }
                } else {
                     sendJsonResponse(exchange, 400, MessageUtils.toJson(new ErrorResponse("Unsupported 'param'. Use 'command' structure.")));
                    return;
                }
            } else {
                 sendJsonResponse(exchange, 400, MessageUtils.toJson(new ErrorResponse("Missing 'command' or 'param'/'value' parameters")));
                return;
            }
        } else { // Standard command processing
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!"serverId".equals(entry.getKey()) && !"command".equals(entry.getKey())) {
                    // Attempt to parse common types, default to String
                    try {
                        controlParams.put(entry.getKey(), Integer.parseInt(entry.getValue()));
                    } catch (NumberFormatException e1) {
                        if ("true".equalsIgnoreCase(entry.getValue()) || "false".equalsIgnoreCase(entry.getValue())) {
                            controlParams.put(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
                        } else {
                            controlParams.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        }


        // Forward to client/broker's control message sending logic
        // This is a blocking call in the HTTP handler thread. Consider async if long-running.
        ResponseMessage response = clientBroker.sendControlCommand(serverId, commandName, controlParams);
        if (response != null) {
            sendJsonResponse(exchange, "OK".equals(response.getStatus()) ? 200 : 500, MessageUtils.toJson(response));
        } else {
            sendJsonResponse(exchange, 500, MessageUtils.toJson(new ErrorResponse("Failed to send command or receive response from server.")));
        }
    }

    private Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) {
                result.put(pair[0], pair[1]);
            } else {
                result.put(pair[0], "");
            }
        }
        return result;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String jsonBody) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8"); // [cite: 52]
        byte[] responseBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    // Simple class for error responses
    private static class ErrorResponse {
        @SuppressWarnings("unused")
		private String error;
        public ErrorResponse(String error) { this.error = error; }

    }
}