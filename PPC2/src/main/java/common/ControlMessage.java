package common;

import java.util.HashMap;
import java.util.Map;
import com.google.gson.JsonSyntaxException; // Para el manejo de errores de GSON

public class ControlMessage extends AbstractMessage {
    private static final long serialVersionUID = 1L;
    private String command;
    private String targetServerId; 
    private Map<String, Object> parameters;
    private String messageType = "CONTROL"; // Para ayudar a GSON

    // Constructor para GSON (puede ser private si GSON usa reflection)
    private ControlMessage() {
        super();
        this.parameters = new HashMap<>();
        this.setEncodingFormat(MessageUtils.ENCODING_JSON); 
    }
    
    public ControlMessage(String command, String targetServerId) {
        this(); // Llama al constructor privado/por defecto
        this.command = command;
        this.targetServerId = targetServerId;
    }

    // Getters y Setters
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getTargetServerId() { return targetServerId; }
    public void setTargetServerId(String targetServerId) { this.targetServerId = targetServerId; }
    public Map<String, Object> getParameters() { return parameters; }
    public void addParameter(String key, Object value) { this.parameters.put(key, value); }
    public String getMessageType() { return messageType; }


    public String serialize() { 
        // El cliente siempre envía mensajes de control como JSON (sin prefijo de tipo en el payload)
        return MessageUtils.toJson(this); // GSON serializará el objeto 'this'
    }

    public static ControlMessage deserialize(String jsonData) {
        try {
            ControlMessage msg = MessageUtils.fromJson(jsonData, ControlMessage.class);
            if (msg != null) {
                msg.setEncodingFormat(MessageUtils.ENCODING_JSON); // El formato es implícitamente JSON
            }
            return msg;
        } catch (JsonSyntaxException e) {
            System.err.println("Error parsing JSON control message with GSON: " + e.getMessage());
            return null;
        }
    }
}