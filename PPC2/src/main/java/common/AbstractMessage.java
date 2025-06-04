package common;

import java.io.Serializable;


public abstract class AbstractMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    protected String messageId;
    protected long timestamp;
    protected String encodingFormat; // XML or JSON, for the payload

    public AbstractMessage() {
        this.messageId = java.util.UUID.randomUUID().toString();
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getMessageId() { return messageId; }
    public long getTimestamp() { return timestamp; }
    public String getEncodingFormat() { return encodingFormat; }
    public void setEncodingFormat(String encodingFormat) { this.encodingFormat = encodingFormat; }

}