package gateway;

public class SmtpConfig {
    // Envío (SMTP)
    public final String smtpHost;
    public final String smtpPort;
    public final String smtpUser;
    public final String smtpPassword;
    public final boolean useTlsSmtp; // Para STARTTLS en SMTP

    // Recepción (POP3/IMAP)
    public final String storeProtocol; // "pop3" o "imap"
    public final String incomingHost;
    public final String incomingPort;
    public final String incomingUser; // A menudo el mismo que smtpUser
    public final String incomingPassword; // A menudo la misma que smtpPassword
    public final boolean useSslStore; // true para "pop3s", "imaps" o si el puerto lo implica

    // Dirección de correo del servicio
    public final String serviceEmailAddress;

    // Intervalo de sondeo para nuevos correos
    public final int pollingIntervalSeconds;

    public SmtpConfig(String smtpHost, String smtpPort, String smtpUser, String smtpPassword, boolean useTlsSmtp,
                      String storeProtocol, String incomingHost, String incomingPort,
                      String incomingUser, String incomingPassword, boolean useSslStore,
                      String serviceEmailAddress, int pollingIntervalSeconds) {

        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
        this.useTlsSmtp = useTlsSmtp;

        this.storeProtocol = storeProtocol;
        this.incomingHost = incomingHost;
        this.incomingPort = incomingPort;
        this.incomingUser = incomingUser;
        this.incomingPassword = incomingPassword;
        this.useSslStore = useSslStore;

        this.serviceEmailAddress = serviceEmailAddress;
        this.pollingIntervalSeconds = pollingIntervalSeconds > 0 ? pollingIntervalSeconds : 60; // Default 60s si es inválido
    }
    public String getUser() {
    	return new String(smtpUser);
    }
}
