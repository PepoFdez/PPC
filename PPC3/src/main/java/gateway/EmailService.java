package gateway;

import client.Client;
import common.DistributionMessage;
import common.MessageUtils;
import common.WeatherVariable;

// Imports para javax.mail
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.search.FlagTerm;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.util.ByteArrayDataSource; // Específico de javax.mail

import java.nio.charset.StandardCharsets; // Para UTF-8
import java.util.Properties;
import java.util.Date;
import java.util.List;
import java.util.Locale; // Para formateo de números
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

    // Método para obtener la dirección de correo del servicio (útil para Client.showHelp)
    public String getServiceEmailAddress() {
        return config.serviceEmailAddress;
    }

    private void initializeSessions() {
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
        Properties storeProps = new Properties();
        final String protocolKeyPrefix = "mail." + config.storeProtocol + ".";
        storeProps.put(protocolKeyPrefix + "host", config.incomingHost);
        storeProps.put(protocolKeyPrefix + "port", config.incomingPort);

        if (config.useSslStore) {
            storeProps.put(protocolKeyPrefix + "ssl.enable", "true");
        }

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
        pollingThread.setDaemon(true);
        pollingThread.start();
        // El mensaje de inicio se puede manejar en Client.java
    }

    public void stop() {
        running = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            try {
                pollingThread.join(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
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
                    store.connect(config.incomingUser, config.incomingPassword);
                    System.out.println("EmailService: Connected to " + config.storeProtocol + " store.");
                }
                inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_WRITE);

                Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                if (messages.length > 0) {
                    System.out.println("EmailService: Found " + messages.length + " unseen messages.");
                }

                for (Message message : messages) {
                    String messageIdForLog = "";
                    boolean alreadyProcessed = false;

                    if ("imap".equalsIgnoreCase(config.storeProtocol)) {
                        if (inbox instanceof UIDFolder) {
                           UIDFolder uidFolder = (UIDFolder) inbox;
                           long uid = uidFolder.getUID(message);
                           messageIdForLog = "UID-" + uid;
                           if (processedMessageUIDs.contains(messageIdForLog)) {
                               alreadyProcessed = true;
                           }
                        } else {
                            String[] headers = message.getHeader("Message-ID");
                            if (headers != null && headers.length > 0) messageIdForLog = headers[0]; else messageIdForLog = "MsgNum-" + message.getMessageNumber();
                        }
                    } else { // POP3
                        String[] headers = message.getHeader("Message-ID");
                        if (headers != null && headers.length > 0) messageIdForLog = headers[0]; else messageIdForLog = "MsgNum-" + message.getMessageNumber();
                    }

                    if (alreadyProcessed) {
                        System.out.println("EmailService: Skipping already processed message " + messageIdForLog);
                        message.setFlag(Flags.Flag.SEEN, true);
                        continue;
                    }

                    String subject = message.getSubject();
                    Address[] fromAddresses = message.getFrom();
                    String from = (fromAddresses != null && fromAddresses.length > 0) ? ((InternetAddress) fromAddresses[0]).getAddress() : "unknown@example.com";
                    
                    System.out.println("EmailService: Processing email from " + from + " with subject: \"" + subject + "\" (ID: " + messageIdForLog + ")");

                    if (subject != null) {
                        String trimmedSubject = subject.trim().toUpperCase();
                        if (trimmedSubject.equals("GET_DATA")) {
                            processGetDataRequest(from, subject);
                            if ("imap".equalsIgnoreCase(config.storeProtocol) && messageIdForLog.startsWith("UID-")) {
                                processedMessageUIDs.add(messageIdForLog);
                            }
                        } else {
                             System.out.println("EmailService: Unknown command in subject: " + subject);
                        }
                    } else {
                         System.out.println("EmailService: Email with no subject from " + from);
                    }
                    message.setFlag(Flags.Flag.SEEN, true);
                }

            } catch (AuthenticationFailedException afe) {
                System.err.println("EmailService: Authentication failed for " + config.storeProtocol + " account " + config.incomingUser + ". Check credentials.");
                System.err.println("Error details: " + afe.getMessage());
                running = false; 
            } catch (MessagingException e) {
                if (running) {
                    System.err.println("EmailService polling error: " + e.getMessage());
                    // e.printStackTrace(); 
                    if (inbox != null && inbox.isOpen()) {
                        try { inbox.close(false); } catch (MessagingException ignored) {}
                    }
                    if (store != null && store.isConnected()) {
                        try { store.close(); } catch (MessagingException ignored) {}
                    }
                    try { Thread.sleep(30000); } catch (InterruptedException interEx) { Thread.currentThread().interrupt(); }
                }
            } finally {
                if (inbox != null && inbox.isOpen()) {
                    try {
                        inbox.close("pop3".equalsIgnoreCase(config.storeProtocol));
                    } catch (MessagingException e) {
                        System.err.println("Error closing inbox: " + e.getMessage());
                    }
                }
            }

            try {
                Thread.sleep(config.pollingIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                if (!running) {
                    System.out.println("EmailService polling thread interrupted for shutdown.");
                    break; 
                }
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
                    // Usar Locale.US para formatear el double con punto decimal
                    sb.append(String.format(Locale.US,"  - %s: %.2f %s\n", var.getName(), var.getValue(), var.getUnit()));
                }
                sb.append("\n");

                try {
                    String fullSerialization = msg.serialize();
                    String jsonPayload = null;
                    String xmlPayload = null;

                    // Preparar JSON
                    if (MessageUtils.ENCODING_JSON.equals(msg.getEncodingFormat())) {
                        if (fullSerialization != null && fullSerialization.startsWith(MessageUtils.ENCODING_JSON + ":")) {
                             jsonPayload = fullSerialization.substring((MessageUtils.ENCODING_JSON + ":").length());
                        } else {
                             jsonPayload = MessageUtils.toJson(msg);
                        }
                    } else { // Si el formato original no es JSON, generar JSON desde el objeto
                        jsonPayload = MessageUtils.toJson(msg);
                    }
                    
                    MimeBodyPart jsonAttachment = new MimeBodyPart();
                    DataSource jsonDataSource = new ByteArrayDataSource(jsonPayload.getBytes(StandardCharsets.UTF_8), "application/json");
                    jsonAttachment.setDataHandler(new DataHandler(jsonDataSource));
                    jsonAttachment.setFileName("weather_data_" + msg.getServerId() + ".json");
                    attachments.add(jsonAttachment);

                    // Preparar XML
                    if (MessageUtils.ENCODING_XML.equals(msg.getEncodingFormat())) {
                         if (fullSerialization != null && fullSerialization.startsWith(MessageUtils.ENCODING_XML + ":")) {
                             xmlPayload = fullSerialization.substring((MessageUtils.ENCODING_XML + ":").length());
                         } else {
                             // Asume que este método existe y devuelve XML puro
                             xmlPayload = msg.serializeToXmlStringForAttachment(); 
                         }
                    } else { // Si el formato original no es XML, generar XML desde el objeto
                        // Asume que este método existe y devuelve XML puro
                        xmlPayload = msg.serializeToXmlStringForAttachment(); 
                    }

                    if (xmlPayload != null && !xmlPayload.isEmpty() && !xmlPayload.startsWith("<!--")) { 
                        MimeBodyPart xmlAttachment = new MimeBodyPart();
                        DataSource xmlDataSource = new ByteArrayDataSource(xmlPayload.getBytes(StandardCharsets.UTF_8), "application/xml");
                        xmlAttachment.setDataHandler(new DataHandler(xmlDataSource));
                        xmlAttachment.setFileName("weather_data_" + msg.getServerId() + ".xml");
                        attachments.add(xmlAttachment);
                    }

                } catch (Exception e) { // Captura más genérica para errores de adjuntos
                     System.err.println("Error creating attachment for " + msg.getServerId() + ": " + e.getMessage());
                     e.printStackTrace(); 
                }
            }
            textBody = sb.toString();
        }
        sendEmail(recipientEmail, responseSubject, textBody, attachments);
    }

    private void sendEmail(String to, String subject, String textBody, List<MimeBodyPart> attachments) {
        try {
            MimeMessage emailMessage = new MimeMessage(sendSession); 
            emailMessage.setFrom(new InternetAddress(config.serviceEmailAddress));
            emailMessage.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to)); 
            emailMessage.setSubject(subject);
            emailMessage.setSentDate(new Date());

            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(textBody, "UTF-8", "plain");

            Multipart multipart = new MimeMultipart(); 
            multipart.addBodyPart(textPart);

            if (attachments != null) {
                for (MimeBodyPart attachmentPart : attachments) {
                    multipart.addBodyPart(attachmentPart); 
                }
            }
            emailMessage.setContent(multipart); 
            
            Transport.send(emailMessage);
            System.out.println("EmailService: Sent email response to " + to + " with subject: \"" + subject + "\"");

        } catch (MessagingException e) {
            System.err.println("EmailService: Error sending email to " + to + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
}
