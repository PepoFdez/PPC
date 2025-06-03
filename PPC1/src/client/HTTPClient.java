package client;

import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.nio.charset.StandardCharsets;

public class HTTPClient {

    private String cookie = "";
    private static final String COOKIE_FILE = "cookie_http.txt"; // Archivo de cookie específico para HTTP

    public HTTPClient() {
        loadCookie();
    }

    private void loadCookie() {
        File file = new File(COOKIE_FILE);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                String loadedCookie = reader.readLine();
                if (loadedCookie != null) {
                    this.cookie = loadedCookie;
                }
            } catch (IOException e) {
                System.err.println("Error al cargar la cookie HTTP: " + e.getMessage());
                // No es crítico, se continuará sin cookie si falla la carga
            }
        }
    }

    public void saveCookie() {
        if (cookie == null || cookie.isEmpty()) return; // No guardar si no hay cookie

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(COOKIE_FILE, StandardCharsets.UTF_8))) {
            writer.write(this.cookie);
        } catch (IOException e) {
            System.err.println("Error al guardar la cookie HTTP: " + e.getMessage());
        }
    }

    public void sendRequest(Scanner scanner) {
        Socket miCliente = null;
        BufferedReader entrada = null;
        DataOutputStream salida = null;

        try {
            miCliente = new Socket("localhost", 8080);
            // Usar UTF-8 para la lectura de la respuesta para compatibilidad
            entrada = new BufferedReader(new InputStreamReader(miCliente.getInputStream(), StandardCharsets.UTF_8));
            salida = new DataOutputStream(miCliente.getOutputStream());

            System.out.println("Ingresa el recurso que deseas solicitar (ej: /index.html):");
            String recurso = scanner.nextLine();
            if (recurso.isEmpty() || !recurso.startsWith("/")) {
                recurso = "/" + recurso; // Asegurar que el recurso empiece con /
            }

            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append("GET ").append(recurso).append(" HTTP/1.1\r\n");
            requestBuilder.append("Host: localhost\r\n");
            requestBuilder.append("Connection: close\r\n"); // Indicar que la conexión se cerrará después de la respuesta
            if (!cookie.isEmpty()) {
                requestBuilder.append("Cookie: ").append(cookie).append("\r\n");
            }
            requestBuilder.append("\r\n"); // Fin de las cabeceras

            System.out.println("\n--- Enviando Petición HTTP ---");
            System.out.print(requestBuilder.toString());
            salida.write(requestBuilder.toString().getBytes(StandardCharsets.UTF_8));
            salida.flush();

            System.out.println("\n--- Respuesta del Servidor HTTP ---");
            String respuesta;
            boolean headersEnded = false;
            StringBuilder htmlContent = new StringBuilder();

            while ((respuesta = entrada.readLine()) != null) {
                System.out.println(respuesta);
                if (respuesta.startsWith("Set-Cookie:")) {
                    this.cookie = respuesta.substring("Set-Cookie:".length()).trim().split(";", 2)[0]; // Tomar solo el valor de la cookie
                    saveCookie(); // Guardar la nueva cookie inmediatamente
                }
                if (headersEnded) {
                    htmlContent.append(respuesta).append(System.lineSeparator());
                }
                if (respuesta.isEmpty()) { // Línea vacía indica fin de cabeceras
                    headersEnded = true;
                }
            }
            // Si se desea, se puede mostrar solo el contenido HTML después de las cabeceras
            // System.out.println("\n--- Contenido HTML Recibido ---");
            // System.out.println(htmlContent.toString());

        } catch (IOException e) {
            System.err.println("Error en la comunicación HTTP: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (entrada != null) entrada.close();
                if (salida != null) salida.close();
                if (miCliente != null) miCliente.close();
            } catch (IOException e) {
                System.err.println("Error al cerrar recursos del cliente HTTP: " + e.getMessage());
            }
        }
    }
}