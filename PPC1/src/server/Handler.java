package server;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringTokenizer;

public class Handler extends Thread {

    private final Socket clientSocket;
    // Mapa compartido para contar recursos (sincronizado externamente o usar ConcurrentHashMap)
    private static Map<String, Integer> sharedResourceCount; 

    public Handler(Socket socket, Map<String, Integer> resourceCountMap) {
        this.clientSocket = socket;
        // Si es la primera vez o si se quiere un mapa por servidor, se podría inicializar aquí
        // pero para un conteo global entre HTTP y HTTPS, se pasa desde el servidor.
        if (sharedResourceCount == null && resourceCountMap != null) {
             sharedResourceCount = resourceCountMap;
        } else if (resourceCountMap != null && sharedResourceCount != resourceCountMap) {
            // Esto podría indicar un error de configuración si se espera un solo mapa global.
            // Por ahora, simplemente usamos el que se pasa.
            sharedResourceCount = resourceCountMap;
        }
    }

    @Override
    public void run() {
        // Usar try-with-resources para asegurar el cierre del socket y los streams
        try (Socket socketToUse = this.clientSocket; // Usa la variable final del constructor
             BufferedReader sIn = new BufferedReader(new InputStreamReader(socketToUse.getInputStream(), StandardCharsets.UTF_8));
             PrintStream sOut = new PrintStream(socketToUse.getOutputStream(), true, StandardCharsets.UTF_8.name())) {

            String requestLine = sIn.readLine(); // Recibo la primera línea (línea de petición)
            if (requestLine == null || requestLine.isEmpty()) {
                // Petición vacía o inválida
                sendErrorResponse(sOut, 400, "Bad Request");
                return;
            }

            System.out.println("Petición recibida de " + socketToUse.getRemoteSocketAddress() + ": " + requestLine);

            StringTokenizer tokenizer = new StringTokenizer(requestLine);
            String method = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;
            String resourcePath = tokenizer.hasMoreTokens() ? tokenizer.nextToken() : null;

            if ("GET".equalsIgnoreCase(method) && resourcePath != null) {
                String clientCookie = null;
                String headerLine;
                // Leer todas las cabeceras
                while ((headerLine = sIn.readLine()) != null && !headerLine.isEmpty()) {
                    // System.out.println("Header: " + headerLine); // Para depuración
                    if (headerLine.toLowerCase().startsWith("cookie:")) {
                        clientCookie = headerLine.substring("cookie:".length()).trim();
                    }
                }

                if (sharedResourceCount != null) {
                    synchronized (sharedResourceCount) {
                        sharedResourceCount.put(resourcePath, sharedResourceCount.getOrDefault(resourcePath, 0) + 1);
                    }
                }
                
                String currentHistory = (clientCookie != null && !clientCookie.isEmpty()) ? clientCookie + ", " + resourcePath : resourcePath;
                String[] historyItems = currentHistory.split(",\\s*");
                int numResourcesInHistory = historyItems.length;

                // Enviar respuesta
                sOut.println("HTTP/1.1 200 OK");
                sOut.println("Content-Type: text/html; charset=utf-8");
                sOut.println("Set-Cookie: " + currentHistory + "; Path=/; SameSite=Lax"); // Path y otros atributos de cookie
                sOut.println("Connection: close"); // Indicar que el servidor cerrará la conexión
                sOut.println(); // Línea en blanco final de las cabeceras

                sOut.println("<!DOCTYPE html>");
                sOut.println("<html lang=\"es\">");
                sOut.println("<head><meta charset=\"UTF-8\"><title>Historial de Acceso</title></head>");
                sOut.println("<body>");
                sOut.println("<h1>Información de Acceso</h1>");
                sOut.println("<p><strong>Último recurso solicitado:</strong> " + resourcePath + "</p>");
                sOut.println("<p><strong>Número de recursos en el historial:</strong> " + numResourcesInHistory + "</p>");
                sOut.println("<p><strong>Historial completo de recursos:</strong> " + currentHistory + "</p>");
                
                if (sharedResourceCount != null) {
                    sOut.println("<h2>Conteo de Solicitudes por Recurso (Global):</h2>");
                    sOut.println("<ul>");
                    synchronized (sharedResourceCount) {
                        for (Map.Entry<String, Integer> entry : sharedResourceCount.entrySet()) {
                            sOut.println("<li>" + entry.getKey() + ": " + entry.getValue() + " veces</li>");
                        }
                    }
                    sOut.println("</ul>");
                }
                
                sOut.println("</body></html>");
                sOut.flush(); // Asegurar que todo se envíe

            } else {
                // Método no soportado o petición mal formada
                sendErrorResponse(sOut, 405, "Method Not Allowed (Only GET is supported)");
            }

        } catch (IOException e) {
            System.err.println("Error en el Handler para " + (clientSocket != null ? clientSocket.getRemoteSocketAddress() : "socket desconocido") + ": " + e.getMessage());
        } finally {

            System.out.println("Conexión cerrada con " + (clientSocket != null ? clientSocket.getRemoteSocketAddress() : "socket desconocido"));
        }
    }
    
    private void sendErrorResponse(PrintStream sOut, int statusCode, String statusMessage) {
        sOut.println("HTTP/1.1 " + statusCode + " " + statusMessage);
        sOut.println("Content-Type: text/html; charset=utf-8");
        sOut.println("Connection: close");
        sOut.println();
        sOut.println("<!DOCTYPE html><html><head><title>Error " + statusCode + "</title></head><body><h1>Error " + statusCode + ": " + statusMessage + "</h1></body></html>");
        sOut.flush();
    }
}