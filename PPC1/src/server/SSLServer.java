package server;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import javax.net.ssl.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SSLServer extends Thread {

    // Nombres de archivos y contraseñas para los keystores y truststores del servidor
    static public final String SERVER_KEYSTORE_FILE = "certs/servidor.jks"; // Keystore del servidor (con su cert y clave privada)
    static public final String SERVER_KEYSTORE_PASSWORD = "cookie"; // Contraseña del keystore del servidor
    static public final String SERVER_KEY_PASSWORD = "cookie";      // Contraseña de la clave privada del servidor (si es diferente)

    // Truststore del servidor (con los CAs que confía para los certificados de cliente)
    static public final String SERVER_TRUSTSTORE_FILE = "certs/servertrust.jks"; // O el CA.jks si es el mismo que usa el cliente
    static public final String SERVER_TRUSTSTORE_PASSWORD = "cookie";

    private final int port;
    // Mapa compartido para el conteo de recursos, sincronizado
    private static final Map<String, Integer> resourceAccessCount = Collections.synchronizedMap(new HashMap<>());
    private SSLServerSocket sslServerSocket = null;
    private final ExecutorService pool;

    public SSLServer(int port) {
        this.port = port;
        this.pool = Executors.newFixedThreadPool(10); // Pool de hilos
    }

    // Constructor por defecto
    public SSLServer() {
        this(4430); // Puerto por defecto para HTTPS de la práctica
    }

    @Override
    public void run() {
        try {
            KeyStore ksServer = KeyStore.getInstance("JKS");
            ksServer.load(new FileInputStream(SERVER_KEYSTORE_FILE), SERVER_KEYSTORE_PASSWORD.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ksServer, SERVER_KEY_PASSWORD.toCharArray());

            KeyStore tsServer = KeyStore.getInstance("JKS");
            tsServer.load(new FileInputStream(SERVER_TRUSTSTORE_FILE), SERVER_TRUSTSTORE_PASSWORD.toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(tsServer);

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2"); // o TLSv1.3
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();
            sslServerSocket = (SSLServerSocket) sslServerSocketFactory.createServerSocket(this.port);

            // Requerir autenticación de cliente
            sslServerSocket.setNeedClientAuth(true);


            System.out.println("Servidor HTTPS iniciado en el puerto " + this.port);
            System.out.println("Esperando conexiones HTTPS (con autenticación de cliente)...");

            while (!sslServerSocket.isClosed()) {
                try {
                    Socket clientSocket = sslServerSocket.accept(); // Es un SSLSocket
                    System.out.println("Cliente HTTPS conectado desde: " + clientSocket.getRemoteSocketAddress());

                    pool.execute(new Handler(clientSocket, resourceAccessCount));
                } catch (SSLPeerUnverifiedException e) {
                    System.err.println("Fallo de autenticación de cliente SSL: " + e.getMessage() + " desde ");
                } catch (IOException e) {
                     if (sslServerSocket.isClosed()) {
                        System.out.println("Servidor HTTPS detenido.");
                        break; 
                    }
                    System.err.println("Error al aceptar conexión de cliente SSL: " + e.getMessage());
                }
            }
        } catch (NoSuchAlgorithmException | KeyStoreException | CertificateException | UnrecoverableKeyException | KeyManagementException | IOException e) {
            System.err.println("Error crítico al iniciar el servidor SSL: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }
    
    public void shutdown() {
        System.out.println("Cerrando servidor HTTPS...");
        pool.shutdown();
        try {
            if (sslServerSocket != null && !sslServerSocket.isClosed()) {
                sslServerSocket.close();
            }
        } catch (IOException e) {
            System.err.println("Error al cerrar el SSLServerSocket: " + e.getMessage());
        }
        System.out.println("Servidor HTTPS completamente detenido.");
    }
}