package common;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
// import javax.xml.validation.SchemaFactory; // Descomentar si se usa XSD
// import javax.xml.validation.Schema; // Descomentar si se usa XSD
// import javax.xml.XMLConstants; // Descomentar si se usa XSD
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
// import java.io.File; // Descomentar si se usa XSD desde archivo

// --- Imports para GSON ---
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

public class MessageUtils {

    public static final String ENCODING_JSON = "JSON";
    public static final String ENCODING_XML = "XML";

    // Instancia de GSON
    private static final Gson gson = new GsonBuilder()
                                        .setPrettyPrinting() // Para que el JSON sea legible
                                        .create();

    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> classOfT) throws JsonSyntaxException {
        return gson.fromJson(json, classOfT);
    }

    public static String toXml(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

            StringWriter sw = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Error converting to XML", ex);
        }
    }

    public static Document parseXmlString(String xmlString, String dtdSystemIdOrSchemaPath) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        
        // Para validación DTD, el DTD debe estar referenciado en el propio XML (DOCTYPE)
        // y se activa con setValidating(true).
        // Para XSD, se configuraría un Schema.
        if (dtdSystemIdOrSchemaPath != null && !dtdSystemIdOrSchemaPath.isEmpty()) {
            // Ejemplo básico para validación DTD (si el DOCTYPE ya está en el XML)
            factory.setValidating(true); 
            factory.setNamespaceAware(true); // Importante para XSD y a veces para DTD complejos

            // Si fuera XSD:
            // SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            // Schema schema = schemaFactory.newSchema(new File(dtdSystemIdOrSchemaPath));
            // factory.setSchema(schema);
        }

        DocumentBuilder builder = factory.newDocumentBuilder();
        
        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(org.xml.sax.SAXParseException exception) throws SAXException {
                System.err.println("XML Validation Warning: " + exception.getMessage());
            }
            @Override
            public void error(org.xml.sax.SAXParseException exception) throws SAXException {
                System.err.println("XML Validation Error: " + exception.getMessage());
                throw exception;
            }
            @Override
            public void fatalError(org.xml.sax.SAXParseException exception) throws SAXException {
                System.err.println("XML Validation Fatal Error: " + exception.getMessage());
                throw exception;
            }
        });
        
        return builder.parse(new InputSource(new StringReader(xmlString)));
    }
    
    public static void logMessage(String fileName, String content) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName, true))) {
            writer.write(content);
            writer.newLine();
            writer.write("----"); 
            writer.newLine();
           // System.out.println("Logged to " + fileName + ":\n" + content.substring(0, Math.min(content.length(), 100)) + "...");
        } catch (IOException e) {
            System.err.println("Error logging message to " + fileName + ": " + e.getMessage());
        }
    }
}