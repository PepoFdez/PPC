package gateway;

import client.Client;
import common.DistributionMessage;
import common.WeatherVariable;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

public class MeteorologyHtmlHandler implements HttpHandler {
    private final Client clientBroker;

    public MeteorologyHtmlHandler(Client clientBroker) {
        this.clientBroker = clientBroker;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "Method Not Allowed");
            return;
        }

        Map<String, DistributionMessage> data = clientBroker.getLatestServerData();
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta http-equiv=\"refresh\" content=\"10\">"); // Auto-refresh page every 10 seconds
        html.append("    <title>Current Weather Data</title>\n");
        html.append("    <style>\n");
        html.append("        body { font-family: Arial, sans-serif; margin: 20px; background-color: #e6f7ff; color: #333; }\n");
        html.append("        h1 { color: #0056b3; text-align: center; }\n");
        html.append("        .server-data { background-color: #fff; border: 1px solid #b3d9ff; margin-bottom: 20px; padding: 15px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n");
        html.append("        .server-id { font-size: 1.5em; color: #007bff; margin-bottom: 10px; }\n");
        html.append("        .timestamp { font-size: 0.9em; color: #888; margin-bottom: 10px; }\n");
        html.append("        table { width: 100%; border-collapse: collapse; margin-top: 10px; }\n");
        html.append("        th, td { text-align: left; padding: 8px; border-bottom: 1px solid #ddd; }\n");
        html.append("        th { background-color: #f0f8ff; }\n");
        html.append("        .no-data { text-align: center; font-size: 1.2em; color: #777; padding: 20px; }\n");
        html.append("        .footer { text-align: center; margin-top: 20px; font-size: 0.8em; color: #666; }");
        html.append("    </style>\n");
        html.append("</head>\n<body>\n");
        html.append("    <h1>Latest Meteorological Data</h1>\n");
        html.append("    <p class=\"footer\">This page auto-refreshes every 10 seconds. Last updated: ").append(new Date()).append("</p>\n");


        if (data.isEmpty()) {
            html.append("    <p class=\"no-data\">No data received from any server yet. Waiting for updates...</p>\n");
        } else {
            for (Map.Entry<String, DistributionMessage> entry : data.entrySet()) {
                DistributionMessage msg = entry.getValue();
                html.append("    <div class=\"server-data\">\n");
                html.append("        <div class=\"server-id\">Server: ").append(msg.getServerId()).append("</div>\n");
                html.append("        <div class=\"timestamp\">Last Update: ").append(new Date(msg.getTimestamp())).append(" (Encoding: ").append(msg.getEncodingFormat()).append(")</div>\n");
                html.append("        <table>\n");
                html.append("            <tr><th>Variable</th><th>Value</th><th>Unit</th></tr>\n");
                for (WeatherVariable var : msg.getVariables()) {
                    html.append("            <tr>\n");
                    html.append("                <td>").append(var.getName()).append("</td>\n");
                    html.append("                <td>").append(String.format("%.2f", var.getValue())).append("</td>\n");
                    html.append("                <td>").append(var.getUnit()).append("</td>\n");
                    html.append("            </tr>\n");
                }
                html.append("        </table>\n");
                html.append("    </div>\n");
            }
        }
        html.append("<p><a href=\"/\">Back to Main Gateway</a></p>");
        html.append("</body>\n</html>");

        sendResponse(exchange, 200, html.toString(), "text/html");
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