package common;

import com.google.gson.JsonSyntaxException; // Para el manejo de errores de GSON

public class ResponseMessage extends AbstractMessage {
    private static final long serialVersionUID = 1L;
    private String originalMessageId; 
    private String status; 
    private String details;
    private String messageType = "RESPONSE"; // Para ayudar a GSON

    // Constructor para GSON
    private ResponseMessage() {
        super();
        this.setEncodingFormat(MessageUtils.ENCODING_JSON);
    }

    public ResponseMessage(String originalMessageId, String status, String details) {
        this(); // Llama al constructor por defecto
        this.originalMessageId = originalMessageId;
        this.status = status;
        this.details = details;
    }

    // Getters
    public String getOriginalMessageId() { return originalMessageId; }
    public String getStatus() { return status; }
    public String getDetails() { return details; }
    public String getMessageType() { return messageType; }
    
    public String serialize() {
        // Las respuestas del servidor son JSON (sin prefijo de tipo en el payload)
        return MessageUtils.toJson(this); // GSON serializará el objeto 'this'
    }

    public static ResponseMessage deserialize(String jsonData) {
         try {
            ResponseMessage msg = MessageUtils.fromJson(jsonData, ResponseMessage.class);
            if (msg != null) {
                 msg.setEncodingFormat(MessageUtils.ENCODING_JSON);
            }
            return msg;
        } catch (JsonSyntaxException e) {
            System.err.println("Error parsing JSON response message with GSON: " + e.getMessage());
            return null;
        }
    }
}