package common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale; // Importar Locale

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import com.google.gson.JsonSyntaxException;

public class DistributionMessage extends AbstractMessage {
    private static final long serialVersionUID = 1L;
    private String serverId;
    private List<WeatherVariable> variables;
    private String messageType = "DISTRIBUTION";

    public DistributionMessage(String serverId, String encodingFormat) {
        super();
        this.serverId = serverId;
        this.variables = new ArrayList<>();
        this.setEncodingFormat(encodingFormat);
    }

    public void addVariable(WeatherVariable var) {
        this.variables.add(var);
    }

    public String getServerId() { return serverId; }
    public List<WeatherVariable> getVariables() { return variables; }
    public String getMessageType() { return messageType; }

    public String serialize() {
        if (MessageUtils.ENCODING_JSON.equalsIgnoreCase(getEncodingFormat())) {
            return MessageUtils.ENCODING_JSON + ":" + MessageUtils.toJson(this);
        } else {
            return toXmlString(); // Llama al método que genera XML
        }
    }

    private String toXmlString() {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement("distributionMessage");
            doc.appendChild(root);

            root.setAttribute("messageId", getMessageId());
            root.setAttribute("timestamp", String.valueOf(getTimestamp()));
            root.setAttribute("serverId", serverId);
            root.setAttribute("encodingFormat", getEncodingFormat());
            root.setAttribute("messageType", messageType); // Atributo añadido según DTD

            Element varsElement = doc.createElement("variables");
            root.appendChild(varsElement);

            for (WeatherVariable var : variables) {
                Element varElement = doc.createElement("variable");
                varElement.setAttribute("name", var.getName());
                Element valueEl = doc.createElement("value");
                // --- CAMBIO IMPORTANTE: Usar Locale.US para formatear el double ---
                valueEl.appendChild(doc.createTextNode(String.format(Locale.US, "%.2f", var.getValue())));
                // -----------------------------------------------------------------
                Element unitEl = doc.createElement("unit");
                unitEl.appendChild(doc.createTextNode(var.getUnit()));
                varElement.appendChild(valueEl);
                varElement.appendChild(unitEl);
                varsElement.appendChild(varElement);
            }
            // MessageUtils.toXml ahora añade el DOCTYPE si está configurado en el Transformer
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
                if (msg != null) {
                     msg.setEncodingFormat(MessageUtils.ENCODING_JSON);
                }
                return msg;
            } catch (JsonSyntaxException e) {
                System.err.println("Error parsing JSON distribution message: " + e.getMessage());
                return null;
            }
        } else if (MessageUtils.ENCODING_XML.equals(type)) {
            try {
                // El DTD name se pasa para el EntityResolver en el cliente
                Document doc = MessageUtils.parseXmlString(data, "distribution_message.dtd"); 
                Element root = doc.getDocumentElement();
                
                String serverId = root.getAttribute("serverId");
                DistributionMessage msg = new DistributionMessage(serverId, MessageUtils.ENCODING_XML);
                msg.messageId = root.getAttribute("messageId"); 
                msg.timestamp = Long.parseLong(root.getAttribute("timestamp"));


                org.w3c.dom.NodeList varNodes = root.getElementsByTagName("variable");
                for (int i = 0; i < varNodes.getLength(); i++) {
                    Element varElement = (Element) varNodes.item(i);
                    String name = varElement.getAttribute("name");
                    // Double.parseDouble debería funcionar bien si el XML usa "."
                    double value = Double.parseDouble(varElement.getElementsByTagName("value").item(0).getTextContent());
                    String unit = varElement.getElementsByTagName("unit").item(0).getTextContent();
                    msg.addVariable(new WeatherVariable(name, value, unit));
                }
              
                return msg;
            } catch (Exception e) { // Captura más genérica por si parseDouble u otros fallan
                System.err.println("Error parsing XML distribution message: " + e.getMessage());
                e.printStackTrace(); // Útil para ver la NumberFormatException completa si ocurre
                return null;
            }
        }
        return null;
    }
    
    // Método para adjuntos de correo
    public String serializeToXmlStringForAttachment() {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement("distributionMessage"); // Mismo elemento raíz
            doc.appendChild(root);

            root.setAttribute("messageId", getMessageId());
            root.setAttribute("timestamp", String.valueOf(getTimestamp()));
            root.setAttribute("serverId", serverId);
            root.setAttribute("messageType", messageType); // Consistente con toXmlString

            Element varsElement = doc.createElement("variables");
            root.appendChild(varsElement);

            for (WeatherVariable var : variables) {
                Element varElement = doc.createElement("variable");
                varElement.setAttribute("name", var.getName());
                Element valueEl = doc.createElement("value");
                // --- CAMBIO IMPORTANTE: Usar Locale.US para formatear el double ---
                valueEl.appendChild(doc.createTextNode(String.format(Locale.US, "%.2f", var.getValue())));
                // -----------------------------------------------------------------
                Element unitEl = doc.createElement("unit");
                unitEl.appendChild(doc.createTextNode(var.getUnit()));
                varElement.appendChild(valueEl);
                varElement.appendChild(unitEl);
                varsElement.appendChild(varElement);
            }     
            return common.MessageUtils.toXml(doc); 

        } catch (ParserConfigurationException e) {
            System.err.println("Error creating XML for attachment: " + e.getMessage());
            return "<!-- Error generating XML for attachment -->";
        }
    }
}
