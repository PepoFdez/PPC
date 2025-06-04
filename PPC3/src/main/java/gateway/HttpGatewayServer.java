package gateway;

import client.Client;
// Importar HttpsServer y clases relacionadas con SSL
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
//import javax.net.ssl.SSLEngine;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.Executors;

public class HttpGatewayServer {
    private HttpsServer httpsServer; // Cambiado de HttpServer a HttpsServer
    // private HttpServer httpServer; // Opcional: si también quieres servir HTTP en otro puerto
    //private final Client clientBroker;
    private final int httpsPort;

    // Contraseñas para los JKS - ¡CAMBIAR POR LAS REALES Y GESTIONAR DE FORMA SEGURA!
    private static final char[] KEYSTORE_PASSWORD = "cookie".toCharArray(); // Contraseña del servidor.jks
    private static final char[] KEY_PASSWORD = "cookie".toCharArray(); // Contraseña de la clave privada dentro de servidor.jks (puede ser la misma)

    private static final String KEYSTORE_PATH = "src/certs/servidor.jks"; // Ruta al keystore del servidor

    public HttpGatewayServer(int port, Client clientBroker) throws Exception { // Cambiado IOException a Exception por SSL
        //this.clientBroker = clientBroker;
        this.httpsPort = port;

        // Descomentar para debug SSL si hay problemas
        // System.setProperty("javax.net.debug", "ssl,handshake"); 

        SSLContext sslContext = SSLContext.getInstance("TLS"); // O "TLSv1.2", "TLSv1.3"

        // 1. Inicializar KeyStore con el JKS del servidor
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream ksIs = new FileInputStream(KEYSTORE_PATH)) {
            ks.load(ksIs, KEYSTORE_PASSWORD);
        }

        // 2. Inicializar KeyManagerFactory con el KeyStore
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); // O "SunX509"
        kmf.init(ks, KEY_PASSWORD);

        // 3. (Opcional) Inicializar TrustManagerFactory si el servidor necesita confiar en clientes (mutual TLS)
        // Por ahora, para un servidor HTTPS estándar, no es estrictamente necesario si no hay autenticación de cliente con certificado.
        // KeyStore ts = KeyStore.getInstance("JKS");
        // try (InputStream tsIs = new FileInputStream("src/certs/servertrust.jks")) { // El servidor confía en estos CAs/certs
        //    ts.load(tsIs, "trustpassword".toCharArray());
        // }
        // TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        // tmf.init(ts);

        // 4. Inicializar SSLContext
        // sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null); // Con TrustManager
        sslContext.init(kmf.getKeyManagers(), null, null); // Sin TrustManager explícito para el servidor (usará los defaults si es necesario)

        // Crear HttpsServer
        httpsServer = HttpsServer.create(new InetSocketAddress(httpsPort), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    // Obtener el contexto SSL del configurador
                    SSLContext context = getSSLContext();
                    //SSLEngine engine = context.createSSLEngine();
                    params.setNeedClientAuth(false); // Cambiar a true para mutual TLS
                    params.setSSLParameters(context.getDefaultSSLParameters());
                    // Podrías configurar aquí más cosas, como los cipher suites, protocolos, etc.
                    // engine.setEnabledCipherSuites(...);
                    // engine.setEnabledProtocols(...);
                } catch (Exception ex) {
                    System.err.println("Failed to configure HTTPS parameters: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        });
        
        // Los contextos son los mismos, pero ahora servidos sobre HTTPS
        httpsServer.createContext("/", new IndexHtmlHandler(clientBroker));
        httpsServer.createContext("/index.html", new IndexHtmlHandler(clientBroker));
        httpsServer.createContext("/meteorologia.html", new MeteorologyHtmlHandler(clientBroker));
        httpsServer.createContext("/apirest/", new RestApiHandler(clientBroker));
        
        httpsServer.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        httpsServer.start();
        System.out.println("HttpGatewayServer (HTTPS) started on port " + httpsServer.getAddress().getPort());
        // Si también inicias un servidor HTTP:
        // httpServer.start();
        // System.out.println("HttpGatewayServer (HTTP) started on port " + httpServer.getAddress().getPort());
    }

    public void stop() {
        if (httpsServer != null) {
            httpsServer.stop(0);
            System.out.println("HttpGatewayServer (HTTPS) stopped.");
        }
        // if (httpServer != null) {
        //     httpServer.stop(0);
        //     System.out.println("HttpGatewayServer (HTTP) stopped.");
        // }
    }
}