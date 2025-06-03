package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HTTPServer extends Thread {
    private ServerSocket serverSocket = null;
    private final int port;
    // Mapa compartido para el conteo de recursos, sincronizado
    private static final Map<String, Integer> resourceAccessCount = Collections.synchronizedMap(new HashMap<>());
    private final ExecutorService pool;


    public HTTPServer(int port) {
        this.port = port;
        // Un pool de hilos para manejar las conexiones puede ser más eficiente que un hilo por conexión
        // Ajusta el tamaño del pool según sea necesario
        this.pool = Executors.newFixedThreadPool(10); 
    }
    
    // Constructor por defecto si no se especifica puerto
    public HTTPServer() {
        this(8080);
    }


    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Servidor HTTP iniciado en el puerto " + port);
            System.out.println("Esperando conexiones HTTP...");

            while (!serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Cliente HTTP conectado desde: " + clientSocket.getRemoteSocketAddress());
                    // Usar el pool de hilos para ejecutar el Handler
                    pool.execute(new Handler(clientSocket, resourceAccessCount));
                } catch (IOException e) {
                    if (serverSocket.isClosed()) {
                        System.out.println("Servidor HTTP detenido.");
                        break; 
                    }
                    System.err.println("Error al aceptar conexión de cliente HTTP: " + e.getMessage());
                    // Considerar una pausa breve antes de reintentar en caso de ciertos errores
                }
            }
        } catch (IOException e) {
            System.err.println("No se pudo iniciar el servidor HTTP en el puerto " + port + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    public void shutdown() {
        System.out.println("Cerrando servidor HTTP...");
        pool.shutdown(); // Deshabilita nuevas tareas
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error al cerrar el ServerSocket HTTP: " + e.getMessage());
        }
        // Opcional: esperar a que las tareas en ejecución terminen
        // try {
        //     if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
        //         pool.shutdownNow(); // Cancelar tareas en ejecución
        //         if (!pool.awaitTermination(60, TimeUnit.SECONDS))
        //             System.err.println("El pool no terminó");
        //     }
        // } catch (InterruptedException ie) {
        //     pool.shutdownNow();
        //     Thread.currentThread().interrupt();
        // }
        System.out.println("Servidor HTTP completamente detenido.");
    }
}