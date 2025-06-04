package common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.w3c.dom.Document;
import org.xml.sax.EntityResolver; 
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys; // Asegúrate que esta importación esté
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream; 
import java.io.StringReader;
import java.io.StringWriter;

public class MessageUtils {

    public static final String ENCODING_JSON = "JSON";
    public static final String ENCODING_XML = "XML";

    private static final Gson gson = new GsonBuilder()
                                        .setPrettyPrinting()
                                        .create();

    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> classOfT) throws JsonSyntaxException {
        return gson.fromJson(json, classOfT);
    }

    // --- MÉTODO MODIFICADO ---
    public static String toXml(Document doc) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");

            if (doc.getDocumentElement() != null) {
                transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, "distribution_message.dtd");
            } else {
                System.err.println("Advertencia (MessageUtils.toXml): El Document no tiene elemento raíz, no se puede añadir DOCTYPE.");
            }


            StringWriter sw = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception ex) {
            throw new RuntimeException("Error converting to XML", ex);
        }
    }

    public static Document parseXmlString(String xmlString, final String dtdName) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        
        factory.setValidating(true); 
        factory.setNamespaceAware(false); 


        DocumentBuilder builder = factory.newDocumentBuilder();
        
        builder.setEntityResolver(new EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId)
                    throws SAXException, IOException {
                if (systemId != null && systemId.endsWith(dtdName)) {
                    InputStream dtdStream = MessageUtils.class.getClassLoader().getResourceAsStream(dtdName);
                    if (dtdStream != null) {
                       
                        return new InputSource(dtdStream);
                    } else {
                       
                        return null;
                    }
                }
                return null;
            }
        });

        builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                System.err.println("XML Validation Warning: " + exception.getMessage() + " (Line: " + exception.getLineNumber() + ", Column: " + exception.getColumnNumber() + ")");
            }
            @Override
            public void error(SAXParseException exception) throws SAXException {
                System.err.println("XML Validation Error: " + exception.getMessage() + " (Line: " + exception.getLineNumber() + ", Column: " + exception.getColumnNumber() + ")");
                throw exception; 
            }
            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                System.err.println("XML Validation Fatal Error: " + exception.getMessage() + " (Line: " + exception.getLineNumber() + ", Column: " + exception.getColumnNumber() + ")");
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
        } catch (IOException e) {
            System.err.println("Error logging message to " + fileName + ": " + e.getMessage());
        }
    }
}
