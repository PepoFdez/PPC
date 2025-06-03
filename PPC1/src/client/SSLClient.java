package client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Scanner;
import javax.net.ssl.*;

public class SSLClient {
    // Asegúrate de que estas rutas y contraseñas coincidan con tu configuración
    static public final String KSTORE_CLIENT = "certs/client.jks"; // Keystore del cliente con su cert y clave privada
    static public final String TSTORE_CLIENT = "certs/clienttrust.jks"; // Truststore del cliente con el CA o cert del servidor
    static public final String KS_PWD = "cookie"; // Contraseña para el keystore
    static public final String TS_PWD = "cookie"; // Contraseña para el truststore
    static public final String CERT_PWD = "cookie"; // Contraseña para la clave privada del cliente (si es diferente a KS_PWD)

    private String cookie = "";
    private static final String COOKIE_FILE_SSL = "cookie_ssl.txt"; // Archivo de cookie específico para HTTPS

    public SSLClient() {
        loadCookie();
    }

    private void loadCookie() {
        File file = new File(COOKIE_FILE_SSL);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
                String loadedCookie = reader.readLine();
                if (loadedCookie != null) {
                    this.cookie = loadedCookie;
                }
            } catch (IOException e) {
                System.err.println("Error al cargar la cookie SSL: " + e.getMessage());
            }
        }
    }

    public void saveCookie() {
        if (cookie == null || cookie.isEmpty()) return;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(COOKIE_FILE_SSL, StandardCharsets.UTF_8))) {
            writer.write(this.cookie);
        } catch (IOException e) {
            System.err.println("Error al guardar la cookie SSL: " + e.getMessage());
        }
    }

    public SSLSocket createSSLSocket(String address, int port) throws GeneralSecurityException, IOException {
        KeyStore ks;
        KeyManagerFactory kmf;
        KeyStore ts;
        TrustManagerFactory tmf;
        SSLContext ctx;

        // Keystore para la identidad del cliente
        ks = KeyStore.getInstance("JKS");
        ks.load(new FileInputStream(KSTORE_CLIENT), KS_PWD.toCharArray());

        kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); // "SunX509" es común
        kmf.init(ks, CERT_PWD.toCharArray());

        // Truststore para confiar en el servidor
        ts = KeyStore.getInstance("JKS");
        ts.load(new FileInputStream(TSTORE_CLIENT), TS_PWD.toCharArray());

        tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); // "SunX509" es común
        tmf.init(ts);

        ctx = SSLContext.getInstance("TLSv1.2"); // O "TLSv1.3" si es compatible
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        SSLSocketFactory fac = ctx.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket) fac.createSocket(address, port);
        // Opcional: especificar suites de cifrado si es necesario
        // sslSocket.setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});
        sslSocket.startHandshake(); // Iniciar el handshake explícitamente
        return sslSocket;
    }

    public void sendRequest(Scanner scanner) {
        SSLSocket sslSocket = null;
        BufferedReader entrada = null;
        DataOutputStream salida = null;

        try {
            sslSocket = createSSLSocket("localhost", 4430); // Usar "localhost" o la IP/hostname real del servidor

            // Usar UTF-8 para la lectura/escritura
            entrada = new BufferedReader(new InputStreamReader(sslSocket.getInputStream(), StandardCharsets.UTF_8));
            salida = new DataOutputStream(sslSocket.getOutputStream());

            System.out.println("Ingresa el recurso que deseas solicitar (ej: /secure/data):");
            String recurso = scanner.nextLine();
             if (recurso.isEmpty() || !recurso.startsWith("/")) {
                recurso = "/" + recurso; // Asegurar que el recurso empiece con /
            }

            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append("GET ").append(recurso).append(" HTTP/1.1\r\n");
            requestBuilder.append("Host: localhost\r\n"); // O el nombre del host configurado en el certificado del servidor
            requestBuilder.append("Connection: close\r\n");
            if (!cookie.isEmpty()) {
                requestBuilder.append("Cookie: ").append(cookie).append("\r\n");
            }
            requestBuilder.append("\r\n");

            System.out.println("\n--- Enviando Petición HTTPS ---");
            System.out.print(requestBuilder.toString());
            salida.write(requestBuilder.toString().getBytes(StandardCharsets.UTF_8));
            salida.flush();

            System.out.println("\n--- Respuesta del Servidor HTTPS ---");
            String respuesta;
            boolean headersEnded = false;
            StringBuilder htmlContent = new StringBuilder();

            while ((respuesta = entrada.readLine()) != null) {
                System.out.println(respuesta);
                if (respuesta.startsWith("Set-Cookie:")) {
                    this.cookie = respuesta.substring("Set-Cookie:".length()).trim().split(";", 2)[0];
                    saveCookie();
                }
                 if (headersEnded) {
                    htmlContent.append(respuesta).append(System.lineSeparator());
                }
                if (respuesta.isEmpty()) {
                    headersEnded = true;
                }
            }
             // System.out.println("\n--- Contenido HTML Recibido ---");
             // System.out.println(htmlContent.toString());

        } catch (GeneralSecurityException e) {
            System.err.println("Error de seguridad SSL: " + e.getMessage());
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("Error en la comunicación SSL: " + e.getMessage());
            e.printStackTrace();
        } finally {
            try {
                if (entrada != null) entrada.close();
                if (salida != null) salida.close();
                if (sslSocket != null) sslSocket.close();
            } catch (IOException e) {
                System.err.println("Error al cerrar recursos del cliente SSL: " + e.getMessage());
            }
        }
    }
}