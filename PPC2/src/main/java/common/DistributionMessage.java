package common;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import com.google.gson.JsonSyntaxException; // Para el manejo de errores de GSON

public class DistributionMessage extends AbstractMessage {
    private static final long serialVersionUID = 1L;
    private String serverId;
    private List<WeatherVariable> variables;
    private String messageType = "DISTRIBUTION"; // Para ayudar a GSON a serializar este campo

    public DistributionMessage(String serverId, String encodingFormat) {
        super();
        this.serverId = serverId;
        this.variables = new ArrayList<>();
        this.setEncodingFormat(encodingFormat); // Heredado de AbstractMessage
    }


    public void addVariable(WeatherVariable var) {
        this.variables.add(var);
    }

    // Getters y Setters (asegúrate de que todos los campos que quieres serializar tengan getters)
    public String getServerId() { return serverId; }
    public List<WeatherVariable> getVariables() { return variables; }
    public String getMessageType() { return messageType; } // Getter para messageType


    public String serialize() {
        if (MessageUtils.ENCODING_JSON.equalsIgnoreCase(getEncodingFormat())) {
            return MessageUtils.ENCODING_JSON + ":" + MessageUtils.toJson(this); // GSON serializará el objeto 'this'
        } else {
            return toXmlString();
        }
    }

    private String toXmlString() {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement("distributionMessage");
            doc.appendChild(root);

            // Atributos de AbstractMessage y DistributionMessage
            root.setAttribute("messageId", getMessageId());
            root.setAttribute("timestamp", String.valueOf(getTimestamp()));
            root.setAttribute("serverId", serverId);
            root.setAttribute("encodingFormat", getEncodingFormat()); // Viene de AbstractMessage
            root.setAttribute("messageType", messageType);


            Element varsElement = doc.createElement("variables");
            root.appendChild(varsElement);

            for (WeatherVariable var : variables) {
                Element varElement = doc.createElement("variable");
                varElement.setAttribute("name", var.getName());
                Element valueEl = doc.createElement("value");
                valueEl.appendChild(doc.createTextNode(String.format("%.2f", var.getValue())));
                Element unitEl = doc.createElement("unit");
                unitEl.appendChild(doc.createTextNode(var.getUnit()));
                varElement.appendChild(valueEl);
                varElement.appendChild(unitEl);
                varsElement.appendChild(varElement);
            }
            return MessageUtils.ENCODING_XML + ":" + MessageUtils.toXml(doc);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Error creating XML for DistributionMessage", e);
        }
    }
    
    public static DistributionMessage deserialize(String rawData) {
        if (rawData == null || !rawData.contains(":")) {
            System.err.println("Malformed raw data for deserialization: " + rawData);
            return null;
        }
        String type = rawData.substring(0, rawData.indexOf(':'));
        String data = rawData.substring(rawData.indexOf(':') + 1);

        if (MessageUtils.ENCODING_JSON.equals(type)) {
            try {
                DistributionMessage msg = MessageUtils.fromJson(data, DistributionMessage.class);
                msg.setEncodingFormat(MessageUtils.ENCODING_JSON);
                return msg;
            } catch (JsonSyntaxException e) {
                System.err.println("Error parsing JSON distribution message with GSON: " + e.getMessage());
                return null;
            }
        } else if (MessageUtils.ENCODING_XML.equals(type)) {
            try {
                Document doc = MessageUtils.parseXmlString(data, "distribution_message.dtd"); 
                Element root = doc.getDocumentElement();
                
                String serverId = root.getAttribute("serverId");

                DistributionMessage msg = new DistributionMessage(serverId, MessageUtils.ENCODING_XML);
                // Poblar campos de AbstractMessage desde atributos XML
                msg.messageId = root.getAttribute("messageId"); // Asumiendo acceso package-private o protected
                msg.timestamp = Long.parseLong(root.getAttribute("timestamp"));


                org.w3c.dom.NodeList varNodes = root.getElementsByTagName("variable");
                for (int i = 0; i < varNodes.getLength(); i++) {
                    Element varElement = (Element) varNodes.item(i);
                    String name = varElement.getAttribute("name");
                    double value = Double.parseDouble(varElement.getElementsByTagName("value").item(0).getTextContent());
                    String unit = varElement.getElementsByTagName("unit").item(0).getTextContent();
                    msg.addVariable(new WeatherVariable(name, value, unit));
                }
                System.out.println("Mensaje XML (Distribución) validado y parseado correctamente.");
                return msg;
            } catch (Exception e) {
                System.err.println("Error parsing XML distribution message: " + e.getMessage());
                e.printStackTrace();
                return null;
            }
        }
        return null;
    }
}