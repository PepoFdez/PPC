package gateway;

import client.Client;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class IndexHtmlHandler implements HttpHandler {
    private final Client clientBroker;

    public IndexHtmlHandler(Client clientBroker) {
        this.clientBroker = clientBroker;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        // Dynamically generate index.html content [cite: 54]
        StringBuilder htmlBuilder = new StringBuilder();
        htmlBuilder.append("<!DOCTYPE html>\n");
        htmlBuilder.append("<html lang=\"en\">\n<head>\n");
        htmlBuilder.append("    <meta charset=\"UTF-8\">\n");
        htmlBuilder.append("    <title>Meteorological Service Gateway</title>\n");
        htmlBuilder.append("    <style>\n");
        htmlBuilder.append("        body { font-family: Arial, sans-serif; margin: 20px; background-color: #f4f4f4; color: #333; }\n");
        htmlBuilder.append("        h1 { color: #0056b3; }\n");
        htmlBuilder.append("        .container { background-color: #fff; padding: 20px; border-radius: 8px; box-shadow: 0 0 10px rgba(0,0,0,0.1); }\n");
        htmlBuilder.append("        ul { list-style-type: none; padding: 0; }\n");
        htmlBuilder.append("        li { margin-bottom: 10px; }\n");
        htmlBuilder.append("        a { text-decoration: none; color: #007bff; font-weight: bold; }\n");
        htmlBuilder.append("        a:hover { text-decoration: underline; }\n");
        htmlBuilder.append("        .service-section { margin-top: 20px; padding-top: 10px; border-top: 1px solid #eee; }\n");
        htmlBuilder.append("    </style>\n");
        htmlBuilder.append("</head>\n<body>\n");
        htmlBuilder.append("    <div class=\"container\">\n");
        htmlBuilder.append("        <h1>Welcome to the Meteorological Information Service</h1>\n");
        
        // HTTP Access [cite: 54]
        htmlBuilder.append("        <div class=\"service-section\">\n");
        htmlBuilder.append("            <h2>HTTP Access</h2>\n");
        htmlBuilder.append("            <ul>\n");
        htmlBuilder.append("                <li><a href=\"/meteorologia.html\">View Latest Weather Data (HTML)</a></li>\n");
        htmlBuilder.append("            </ul>\n");
        htmlBuilder.append("        </div>\n");

        // REST API Access [cite: 54]
        htmlBuilder.append("        <div class=\"service-section\">\n");
        htmlBuilder.append("            <h2>REST API Access</h2>\n");
        htmlBuilder.append("            <p>Access weather data and control services via REST endpoints (returns JSON).</p>\n");
        htmlBuilder.append("            <ul>\n");
        htmlBuilder.append("                <li><a href=\"/apirest/muestra_valores\">Get All Weather Values (JSON)</a></li>\n");
        htmlBuilder.append("                <li>Example: <code>/apirest/cambia_parametro?serverId=S1&param=frequency&value=2000</code> (GET - for test, ideally POST/PUT)</li>\n");
        // Add more examples or a link to API documentation if available
        htmlBuilder.append("            </ul>\n");
        htmlBuilder.append("        </div>\n");

        // SMTP Access Info [cite: 54]
        htmlBuilder.append("        <div class=\"service-section\">\n");
        htmlBuilder.append("            <h2>SMTP Email Access</h2>\n");
        htmlBuilder.append("            <p>Send an email to the service address (e.g., client-broker@example.com) with commands in the subject or body to receive weather data.</p>\n");
        htmlBuilder.append("            <p>Supported commands (example):</p>\n");
        htmlBuilder.append("            <ul>\n");
        htmlBuilder.append("                <li>Subject: <code>GET_DATA</code> - Retrieves all current weather data.</li>\n");
        // Add more SMTP commands as implemented
        htmlBuilder.append("            </ul>\n");
        htmlBuilder.append("        </div>\n");
        htmlBuilder.append("    </div>\n");
        htmlBuilder.append("</body>\n</html>");

        sendResponse(exchange, 200, htmlBuilder.toString(), "text/html");
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String responseBody, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
    private void sendResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        sendResponse(exchange, statusCode, message, "text/plain");
    }
}