package gateway;

import client.Client;
import common.DistributionMessage;
import common.MessageUtils;
import common.WeatherVariable;

// Cambios de imports de jakarta.mail.* a javax.mail.*
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.search.FlagTerm;

import java.util.Properties;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class EmailService {
    private final SmtpConfig config;
    private final Client clientBroker;
    private Store store; // javax.mail.Store
    private Session sendSession; // javax.mail.Session
    private volatile boolean running = false;
    private Thread pollingThread;
    private final Set<String> processedMessageUIDs = new HashSet<>();

    public EmailService(SmtpConfig config, Client clientBroker) {
        this.config = config;
        this.clientBroker = clientBroker;
        initializeSessions();
    }

    private void initializeSessions() {
        // SMTP Session for sending mail
        Properties smtpProps = new Properties();
        smtpProps.put("mail.smtp.host", config.smtpHost);
        smtpProps.put("mail.smtp.port", config.smtpPort);
        smtpProps.put("mail.smtp.auth", "true");

        if (config.useTlsSmtp) { // Usar STARTTLS
            smtpProps.put("mail.smtp.starttls.enable", "true");
        } else if ("465".equals(config.smtpPort) || (config.smtpHost != null && config.smtpHost.toLowerCase().contains("smtps"))) { // Asumir SSL directo para puerto 465
            smtpProps.put("mail.smtp.socketFactory.port", config.smtpPort);
            smtpProps.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            smtpProps.put("mail.smtp.ssl.enable", "true");
        }

        this.sendSession = Session.getInstance(smtpProps, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.smtpUser, config.smtpPassword);
            }
        });
        // this.sendSession.setDebug(true); // O System.setProperty("javax.mail.debug", "true");

        // POP3/IMAP Session for receiving
        Properties storeProps = new Properties();
        final String protocolKeyPrefix = "mail." + config.storeProtocol + ".";
        storeProps.put(protocolKeyPrefix + "host", config.incomingHost);
        storeProps.put(protocolKeyPrefix + "port", config.incomingPort);

        if (config.useSslStore) {
            storeProps.put(protocolKeyPrefix + "ssl.enable", "true");
        }
        // Timeouts para IMAP (útil para evitar bloqueos indefinidos)
        // storeProps.put("mail.imap.connectiontimeout", "10000"); // 10 segundos
        // storeProps.put("mail.imap.timeout", "10000"); // 10 segundos

        Session receiveSession = Session.getInstance(storeProps);
        // receiveSession.setDebug(true); // O usar System.setProperty("javax.mail.debug", "true");
        try {
            this.store = receiveSession.getStore(config.storeProtocol);
        } catch (NoSuchProviderException e) {
            System.err.println("Error getting mail store for protocol " + config.storeProtocol + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void start() {
        if (store == null) {
            System.err.println("EmailService cannot start: mail store not initialized.");
            return;
        }
        running = true;
        pollingThread = new Thread(this::pollEmails, "EmailPollingThread");
        pollingThread.setDaemon(true); // Para que el hilo no impida que la JVM se cierre
        pollingThread.start();
        // System.out.println("EmailService started. Polling for emails to " + config.serviceEmailAddress); // Ya se imprime desde Client
    }

    public void stop() {
        running = false;
        if (pollingThread != null) {
            pollingThread.interrupt(); // Interrumpir el sleep del hilo de sondeo
            try {
                pollingThread.join(10000); // Esperar un tiempo prudencial para que termine
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restaurar el estado de interrupción
                System.err.println("Email polling thread interrupted while stopping.");
            }
        }
        try {
            if (store != null && store.isConnected()) {
                store.close();
            }
        } catch (MessagingException e) {
            System.err.println("Error closing mail store: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("EmailService stopped.");
    }

    private void pollEmails() {
        while (running) {
            Folder inbox = null;
            try {
                if (!store.isConnected()) {
                    System.out.println("EmailService: Connecting to mail store " + config.storeProtocol + " ("+config.incomingHost+")...");
                    store.connect(config.incomingUser, config.incomingPassword); // El host se toma de las propiedades de la sesión
                }
                inbox = store.getFolder("INBOX"); // Carpeta de entrada estándar
                inbox.open(Folder.READ_WRITE); // READ_WRITE para poder cambiar flags (ej. SEEN, DELETED)

                // Buscar mensajes no leídos/nuevos
                Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                if (messages.length > 0) {
                    System.out.println("EmailService: Found " + messages.length + " unseen messages.");
                }


                for (Message message : messages) {
                    String messageIdForLog = ""; 
                    boolean alreadyProcessed = false;

                    // Evitar procesamiento múltiple usando UID para IMAP
                    if ("imap".equalsIgnoreCase(config.storeProtocol)) {
                        if (inbox instanceof UIDFolder) { // Buena práctica verificar
                           UIDFolder uidFolder = (UIDFolder) inbox;
                           long uid = uidFolder.getUID(message);
                           messageIdForLog = "UID-" + uid;
                           if (processedMessageUIDs.contains(messageIdForLog)) {
                               // System.out.println("EmailService: Skipping already processed IMAP message " + messageIdForLog); // Opcional
                               alreadyProcessed = true;
                           }
                        } else {
                            // UIDFolder no soportado, recurrir a Message-ID si es posible
                            String[] headers = message.getHeader("Message-ID");
                            if (headers != null && headers.length > 0) messageIdForLog = headers[0]; else messageIdForLog = "MsgNum-" + message.getMessageNumber();
                        }
                    } else { // Para POP3, Message-ID es más común para rastreo si no se borran
                        String[] headers = message.getHeader("Message-ID");
                        if (headers != null && headers.length > 0) messageIdForLog = headers[0]; else messageIdForLog = "MsgNum-" + message.getMessageNumber();
                    }

                    if (alreadyProcessed) {
                        message.setFlag(Flags.Flag.SEEN, true); // Asegurar que esté marcado como visto
                        continue;
                    }

                    String subject = message.getSubject();
                    Address[] fromAddresses = message.getFrom();
                    String from = (fromAddresses != null && fromAddresses.length > 0) ? ((InternetAddress) fromAddresses[0]).getAddress() : "unknown@example.com";
                    
                    System.out.println("EmailService: Processing email from " + from + " with subject: \"" + subject + "\" (ID: " + messageIdForLog + ")");

                    // Parsear petición del asunto (requisito Práctica 3)
                    if (subject != null) {
                        String trimmedSubject = subject.trim().toUpperCase();
                        if (trimmedSubject.equals("GET_DATA")) { // Petición de datos
                            processGetDataRequest(from, subject); // Procesar y responder
                            if ("imap".equalsIgnoreCase(config.storeProtocol) && messageIdForLog.startsWith("UID-")) {
                                processedMessageUIDs.add(messageIdForLog); // Añadir a procesados solo si es IMAP y tenemos UID
                            }
                        } else {
                             System.out.println("EmailService: Unknown command in subject: " + subject);
                        }
                    } else {
                         System.out.println("EmailService: Email with no subject from " + from);
                    }

                    message.setFlag(Flags.Flag.SEEN, true); // Marcar como visto para evitar reprocesamiento en la siguiente encuesta
                    // Para POP3, si quieres borrar los mensajes del servidor después de procesarlos:
                    // if ("pop3".equalsIgnoreCase(config.storeProtocol)) {
                    //    message.setFlag(Flags.Flag.DELETED, true);
                    // }
                }

            } catch (AuthenticationFailedException afe) {
                System.err.println("EmailService: Authentication failed for " + config.storeProtocol + " account " + config.incomingUser + ". Check credentials.");
                System.err.println("Error details: " + afe.getMessage());
                running = false; // Detener el sondeo si la autenticación falla para evitar bloqueos/spam
            } catch (MessagingException e) {
                if (running) {
                    System.err.println("EmailService polling error: " + e.getMessage());
                    // e.printStackTrace(); // Descomentar para debug detallado
                    // Intentar cerrar conexiones de forma segura en caso de error
                    if (inbox != null && inbox.isOpen()) {
                        try { inbox.close(false); } catch (MessagingException ignored) {} // false para no expurgar
                    }
                    if (store != null && store.isConnected()) {
                        try { store.close(); } catch (MessagingException ignored) {}
                    }
                    // Pausa antes de reintentar la conexión para no saturar
                    try { Thread.sleep(30000); } catch (InterruptedException interEx) { Thread.currentThread().interrupt(); }
                }
            } finally {
                if (inbox != null && inbox.isOpen()) {
                    try {
                        inbox.close("pop3".equalsIgnoreCase(config.storeProtocol)); // true para expurgar si es POP3 y se marcaron DELETED
                    } catch (MessagingException e) {
                        System.err.println("Error closing inbox: " + e.getMessage());
                    }
                }
            }

            try {
                Thread.sleep(config.pollingIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                if (!running) { // Si 'running' es false, es una interrupción para apagar
                    System.out.println("EmailService polling thread interrupted for shutdown.");
                    break; // Salir del bucle while
                }
                // Si 'running' es true, fue una interrupción inesperada, restaurar flag y continuar (o decidir otra acción)
                Thread.currentThread().interrupt(); 
                System.out.println("EmailService polling thread interrupted unexpectedly, but service still running.");
            }
        }
        System.out.println("EmailService: Polling stopped.");
    }

    private void processGetDataRequest(String recipientEmail, String originalSubject) {
        Map<String, DistributionMessage> allData = clientBroker.getLatestServerData();
        String responseSubject = "Re: " + originalSubject;
        String textBody;
        List<MimeBodyPart> attachments = new ArrayList<>();

        if (allData.isEmpty()) {
            textBody = "No weather data currently available from any station.\n\nPlease try again later.";
        } else {
            StringBuilder sb = new StringBuilder("Dear User,\n\nHere is the latest weather data as requested:\n\n");
            for (Map.Entry<String, DistributionMessage> entry : allData.entrySet()) {
                DistributionMessage msg = entry.getValue();
                sb.append("--- Station: ").append(msg.getServerId()).append(" ---\n");
                sb.append("Last Update: ").append(new Date(msg.getTimestamp())).append("\n");
                sb.append("Encoding used by server: ").append(msg.getEncodingFormat()).append("\n");
                for (WeatherVariable var : msg.getVariables()) {
                    sb.append(String.format("  - %s: %.2f %s\n", var.getName(), var.getValue(), var.getUnit()));
                }
                sb.append("\n");

                try {
                    String fullSerialization = msg.serialize(); // Este método añade "TYPE:"
                    String jsonPayload = null;
                    String xmlPayload = null;

                    // Preparar JSON
                    if (MessageUtils.ENCODING_JSON.equals(msg.getEncodingFormat())) {
                        // Extraer payload si el mensaje ya está serializado con prefijo
                        if (fullSerialization != null && fullSerialization.startsWith(MessageUtils.ENCODING_JSON + ":")) {
                             jsonPayload = fullSerialization.substring((MessageUtils.ENCODING_JSON + ":").length());
                        } else { // Serializar el objeto a JSON si no tiene el prefijo (o como fallback)
                             jsonPayload = MessageUtils.toJson(msg);
                        }
                    } else { // Si el formato original no es JSON, generar JSON desde el objeto
                        jsonPayload = MessageUtils.toJson(msg);
                    }
                    MimeBodyPart jsonAttachment = new MimeBodyPart();
                    jsonAttachment.setContent(jsonPayload, "application/json; charset=UTF-8");
                    jsonAttachment.setFileName("weather_data_" + msg.getServerId() + ".json");
                    attachments.add(jsonAttachment);

                    // Preparar XML
                    if (MessageUtils.ENCODING_XML.equals(msg.getEncodingFormat())) {
                         if (fullSerialization != null && fullSerialization.startsWith(MessageUtils.ENCODING_XML + ":")) {
                             xmlPayload = fullSerialization.substring((MessageUtils.ENCODING_XML + ":").length());
                         } else {
                             // Necesitas un método que serialice a XML puro
                             xmlPayload = msg.serializeToXmlStringForAttachment(); // Asume que este método existe y funciona
                         }
                    } else { // Si el formato original no es XML, generar XML desde el objeto
                        xmlPayload = msg.serializeToXmlStringForAttachment(); // Asume que este método existe y funciona
                    }

                    // Añadir adjunto XML solo si se generó correctamente
                    if (xmlPayload != null && !xmlPayload.isEmpty() && !xmlPayload.startsWith("<!--")) { // Evitar placeholders o XML inválido
                        MimeBodyPart xmlAttachment = new MimeBodyPart();
                        xmlAttachment.setContent(xmlPayload, "application/xml; charset=UTF-8");
                        xmlAttachment.setFileName("weather_data_" + msg.getServerId() + ".xml");
                        attachments.add(xmlAttachment);
                    }

                } catch (Exception e) { 
                     System.err.println("Error creating attachment for " + msg.getServerId() + ": " + e.getMessage());
                }
            }
            textBody = sb.toString();
        }
        // Enviar el correo electrónico con la información recopilada y los adjuntos
        sendEmail(recipientEmail, responseSubject, textBody, attachments);
    }

    private void sendEmail(String to, String subject, String textBody, List<MimeBodyPart> attachments) {
        try {
            MimeMessage emailMessage = new MimeMessage(sendSession); // javax.mail.internet.MimeMessage
            // Configurar remitente del servicio
            emailMessage.setFrom(new InternetAddress(config.serviceEmailAddress));
            emailMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to)); // Destinatario de la respuesta (quien envió la petición)
            emailMessage.setSubject(subject);
            emailMessage.setSentDate(new Date());

            // Cuerpo del mensaje de texto
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(textBody, "UTF-8", "plain");

            // Contenedor multiparte para texto y adjuntos
            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(textPart);

            // Añadir adjuntos (archivos JSON/XML)
            if (attachments != null) {
                for (MimeBodyPart attachmentPart : attachments) {
                    multipart.addBodyPart(attachmentPart);
                }
            }
            emailMessage.setContent(multipart); // El contenido del mensaje es el multiparte
            
            Transport.send(emailMessage);
            System.out.println("EmailService: Sent email response to " + to + " with subject: \"" + subject + "\"");

        } catch (MessagingException e) {
            System.err.println("EmailService: Error sending email to " + to + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
