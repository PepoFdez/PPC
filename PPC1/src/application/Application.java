package application;

import server.HTTPServer;
import server.SSLServer;
import client.HTTPClient;
import client.SSLClient;
import java.util.Scanner;

public class Application {
    public static void main(String[] args) {
        // Usar constructores que permiten especificar el puerto si es necesario
        // o los constructores por defecto para los puertos de la práctica
        HTTPServer serverHTTP = new HTTPServer(); // Puerto por defecto 8080
        SSLServer serverHTTPS = new SSLServer();   // Puerto por defecto 4430

        serverHTTP.start();
        serverHTTPS.start();

        Scanner scanner = new Scanner(System.in);
        HTTPClient clientHTTP = new HTTPClient();
        SSLClient clientSSL = new SSLClient();

        try {
            while (true) {
                System.out.println("\n¿Qué protocolo deseas utilizar? (http/https/salir)");
                String protocol = scanner.nextLine();

                if (protocol.equalsIgnoreCase("http")) {
                    clientHTTP.sendRequest(scanner);
                } else if (protocol.equalsIgnoreCase("https")) {
                    clientSSL.sendRequest(scanner);
                } else if (protocol.equalsIgnoreCase("salir")) {
                    System.out.println("Saliendo de la aplicación...");
                    break;
                } else {
                    System.out.println("'" + protocol + "' no es un protocolo válido. Intenta con http, https o salir.");
                }

                // No preguntar si quiere otra solicitud aquí si los clientes ya manejan múltiples peticiones
                // o si el bucle es para cambiar de protocolo.
                // System.out.println("¿Quieres hacer otra solicitud general? (s/n)");
                // if (!scanner.nextLine().equalsIgnoreCase("s")) {
                //     break;
                // }
            }
        } finally {
            // Detener los servidores cuando la aplicación cliente termina
            System.out.println("Deteniendo servidores...");
            if (serverHTTP != null && serverHTTP.isAlive()) {
                serverHTTP.shutdown();
            }
            if (serverHTTPS != null && serverHTTPS.isAlive()) {
                serverHTTPS.shutdown();
            }
            scanner.close();
            System.out.println("Aplicación finalizada.");
        }
    }
}