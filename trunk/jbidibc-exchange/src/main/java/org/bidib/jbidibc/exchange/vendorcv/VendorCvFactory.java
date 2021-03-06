package org.bidib.jbidibc.exchange.vendorcv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.StringUtils;
import org.bidib.jbidibc.core.Node;
import org.bidib.jbidibc.core.SoftwareVersion;
import org.bidib.jbidibc.core.utils.NodeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

public class VendorCvFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(VendorCvFactory.class);

    private static final String JAXB_PACKAGE = "org.bidib.jbidibc.exchange.vendorcv";

    public static final String XSD_LOCATION = "/xsd/vendor_cv.xsd";

    private static XMLInputFactory XML_INPUT_FACTORY;

    private static JAXBContext JAXBCONTEXT;

    public static synchronized VendorCvData getCvDefinition(Node node, String... searchPaths) {
        LOGGER.info("Load the vendor cv definition for node: {}", node);

        // TODO check the firmware version of the node and verify the correct definition is
        // loaded
        SoftwareVersion softwareVersion = node.getSoftwareVersion();

        return new VendorCvFactory().loadCvDefintionForNode(node.getUniqueId(), softwareVersion, searchPaths);
    }

    private VendorCvData loadCvDefintionForNode(long uniqueId, SoftwareVersion softwareVersion, String... searchPaths) {
        long pid = NodeUtils.getPid(uniqueId);
        long vid = NodeUtils.getVendorId(uniqueId);
        LOGGER.info("Load the vendor cv definition for uniqueId: {}, pid: {}, vid: {}, software version: {}",
            NodeUtils.getUniqueIdAsString(uniqueId), pid, vid, softwareVersion);

        VendorCvData vendorCvData = null;
        for (String searchPath : searchPaths) {
            StringBuffer filename = new StringBuffer("BiDiBCV-");
            filename.append(vid).append("-").append(pid).append(".xml");

            LOGGER.info("Prepared filename to load vendorCv: {}", filename.toString());
            if (searchPath.startsWith("classpath:")) {
                int beginIndex = "classpath:".length();
                String lookup = searchPath.substring(beginIndex) + "/" + filename.toString();
                LOGGER.info("Lookup vendorCv file internally: {}", lookup);

                final StringBuffer filenameSearch = new StringBuffer("*BiDiBCV-");
                filenameSearch.append(vid).append("-").append(pid).append("*").append(".xml");

                final List<File> files = new LinkedList<>();

                URL pathString = VendorCvFactory.class.getResource(lookup);
                LOGGER.info("Prepared pathString: {}", pathString);

                FileSystem fs = null;
                try {

                    URI lookupURI = pathString.toURI();
                    LOGGER.info("Prepared lookupURI: {}", lookupURI);

                    final String[] array = lookupURI.toString().split("!");

                    Path path = null;
                    if (array.length > 1) {
                        final Map<String, String> env = new HashMap<>();
                        LOGGER.info("Create new filesystem for: {}", array[0]);
                        fs = FileSystems.newFileSystem(URI.create(array[0]), env);
                        path = fs.getPath(array[1]);
                        LOGGER.info("Prepared path: {}", path);
                    }
                    else {
                        path = Paths.get(lookupURI);
                    }

                    final FileSystem fsInner = fs;

                    Files.walkFileTree(path.getParent(), new SimpleFileVisitor<Path>() {
                        @Override
                        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                            LOGGER.info("Current file: {}", path);

                            if (FilenameUtils.wildcardMatch(path.toString(), filenameSearch.toString(),
                                IOCase.INSENSITIVE)) {
                                LOGGER.info("Found matching path: {}, absolutePath: {}", path, path.toAbsolutePath());

                                File file = null;
                                if (fsInner != null) {
                                    String filePath = array[0] + "!" + path.toAbsolutePath();
                                    file = new File(filePath);
                                }
                                else {

                                    file = path.toFile();
                                }
                                // File file = path.toFile();
                                LOGGER.info("Add matching file: {}", file);

                                files.add(file);
                            }

                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                            if (e == null) {
                                LOGGER.info("Current directory: {}", dir);
                                return FileVisitResult.CONTINUE;
                            }
                            else {
                                // directory iteration failed
                                throw e;
                            }
                        }
                    });
                }
                catch (Exception e) {
                    LOGGER.warn("Convert uri to path failed.", e);
                }
                finally {
                    if (fs != null) {
                        try {
                            fs.close();
                        }
                        catch (Exception ex) {
                            LOGGER.warn("Close filesystem failed.", ex);
                        }
                    }
                }

                LOGGER.info("Found matching files: {}", files);
                String selectedFileName = null;
                if (CollectionUtils.isNotEmpty(files)) {
                    List<File> fileCollection = new LinkedList<>();
                    for (File file : files) {

                        fileCollection.add(file);

                    }

                    File vendorCvFile = findMatchingVendorCV(fileCollection, filename.toString(), softwareVersion);
                    LOGGER.info("Matching vendorCvFile: {}", vendorCvFile);
                    if (vendorCvFile != null /* && vendorCvFile.exists() */) {
                        String lookupPath = vendorCvFile.getName();

                        selectedFileName = lookupPath;

                        lookup = searchPath.substring(beginIndex) + "/" + lookupPath;
                        // LOGGER.info("Lookup vendorCv file internally: {}", lookup);
                        LOGGER.info("Prepared vendor CV lookup: {}", lookup);
                    }
                    else {
                        LOGGER.warn("No matching vendorCV file found.");
                    }
                }

                InputStream is = VendorCvFactory.class.getResourceAsStream(lookup);
                if (is != null) {
                    LOGGER.info("Lookup directly proceeded: {}", lookup);
                    VendorCV vendorCV = loadVendorCvFile(is);
                    if (vendorCV != null) {
                        if (StringUtils.isBlank(selectedFileName)) {
                            selectedFileName = filename.toString();
                        }
                        vendorCvData = new VendorCvData(vendorCV, selectedFileName);

                        break;
                    }
                }
                else {
                    LOGGER.warn("Internal lookup for products file failed.");
                }
            }
            else {
                LOGGER.info("Search for files in searchPath: {}", searchPath);
                File productsFile = new File(searchPath, filename.toString());
                File searchDirectory = productsFile.getParentFile();
                if (searchDirectory.exists()) {
                    // use filename with wildcards
                    StringBuffer filenameSearch = new StringBuffer("BiDiBCV-");
                    filenameSearch.append(vid).append("-").append(pid).append("*").append(".xml");
                    IOFileFilter fileFilter = new WildcardFileFilter(filenameSearch.toString(), IOCase.INSENSITIVE);
                    Collection<File> files = FileUtils.listFiles(searchDirectory, fileFilter, TrueFileFilter.INSTANCE);

                    LOGGER.info("Found matching files: {}", files);

                    productsFile = findMatchingVendorCV(files, filename.toString(), softwareVersion);

                    LOGGER.info("Use productsFile: {}", productsFile);
                }
                else {
                    LOGGER.info("The directory to search does not exist: {}", searchDirectory.toString());
                }

                // File productsFile = new File(searchPath, filename.toString());
                if (productsFile != null && productsFile.exists()) {
                    LOGGER.info("Found product file: {}", productsFile.getAbsolutePath());
                    // try to load products
                    VendorCV vendorCV = loadVendorCvFile(productsFile);
                    if (vendorCV != null) {

                        vendorCvData = new VendorCvData(vendorCV, productsFile.getName());
                        break;
                    }
                }
                else {
                    LOGGER.info("File does not exist in: {}", searchPath);
                }
            }
        }
        LOGGER.trace("Loaded vendorCvData: {}", vendorCvData);
        return vendorCvData;
    }

    private File findMatchingVendorCV(Collection<File> files, String defaultFilename, SoftwareVersion softwareVersion) {
        if (CollectionUtils.isNotEmpty(files)) {
            LOGGER.info("Search for matching CV definition for software version: {}", softwareVersion);

            File defaultCvDefinition = null;
            File cvDefinition = null;

            // TODO find a better logic to detect the matching CV definition

            for (File file : files) {
                String fileName = FilenameUtils.getBaseName(file.getName());
                LOGGER.info("Check if filename is matching: {}", fileName);

                int index = fileName.lastIndexOf("-");
                if (index > -1 && index < fileName.length()) {
                    String lastPart = fileName.substring(index + 1);
                    // scan version
                    if (lastPart.matches("^\\d(\\.\\d)+")) {
                        String[] splited = lastPart.split("\\.");

                        LOGGER.info("Found version schema: {}", (Object[]) splited);

                        int majorVersion = Integer.parseInt(splited[0]);
                        int minorVersion = Integer.parseInt(splited[1]);
                        int microVersion = 0;
                        if (splited.length > 2) {
                            microVersion = Integer.parseInt(splited[2]);
                        }

                        LOGGER.info("Found version, major: {}, minor: {}, micro: {}", majorVersion, minorVersion,
                            microVersion);

                        SoftwareVersion current = new SoftwareVersion(majorVersion, minorVersion, microVersion);
                        if (softwareVersion.compareTo(current) == 0) {
                            LOGGER.info("Found excactly matching version in file: {}", file);

                            cvDefinition = file;
                            break;
                        }
                    }
                    else {
                        LOGGER.info("Last part does not match version schema: {}", lastPart);
                    }
                }

                if (file.getName().equalsIgnoreCase(defaultFilename)) {
                    LOGGER.info("Found the default CV definition: {}", file);
                    defaultCvDefinition = file;
                }
            }

            if (cvDefinition == null) {
                LOGGER.info("Use defaultCvDefinition: {}", defaultCvDefinition);
                cvDefinition = defaultCvDefinition;
            }

            return cvDefinition;
        }

        return null;
    }

    private VendorCV loadVendorCvFile(File vendorCvFile) {
        VendorCV vendorCV = null;
        InputStream is;
        try {
            is = new FileInputStream(vendorCvFile);
            vendorCV = loadVendorCvFile(is);
        }
        catch (FileNotFoundException ex) {
            LOGGER.info("No vendorCV file found.");
        }
        return vendorCV;
    }

    private VendorCV loadVendorCvFile(InputStream is) {

        VendorCV vendorCV = null;

        try {
            if (XML_INPUT_FACTORY == null) {
                XMLInputFactory factory = XMLInputFactory.newInstance();
                factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
                factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.FALSE);
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
                factory.setXMLResolver(new XMLResolver() {
                    public Object resolveEntity(String publicID, String systemID, String baseURI, String namespace)
                        throws XMLStreamException {
                        throw new XMLStreamException("Reading external entities is disabled");
                    }
                });
                XML_INPUT_FACTORY = factory;
            }

            if (JAXBCONTEXT == null) {
                JAXBContext jaxbContext = JAXBContext.newInstance(JAXB_PACKAGE);
                JAXBCONTEXT = jaxbContext;
            }

            Unmarshaller unmarshaller = JAXBCONTEXT.createUnmarshaller();
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            StreamSource streamSource = new StreamSource(VendorCvFactory.class.getResourceAsStream(XSD_LOCATION));
            Schema schema = schemaFactory.newSchema(streamSource);
            unmarshaller.setSchema(schema);

            // vendorCV = (VendorCV) unmarshaller.unmarshal(is);

            vendorCV = (VendorCV) unmarshaller.unmarshal(XML_INPUT_FACTORY.createXMLStreamReader(is));
        }
        catch (JAXBException | SAXException | XMLStreamException ex) {
            LOGGER.warn("Load VendorCV from file failed.", ex);
        }
        return vendorCV;
    }

    public static Integer getNumberOfPorts(String portType, Node node, String... searchPaths) {
        LOGGER.info("Load number of configured port from the vendor cv definition for node: {}", node);

        return new VendorCvFactory().getNumberOfPorts(portType, node.getUniqueId(), searchPaths);
    }

    private Integer getNumberOfPorts(String portType, long uniqueId, String... searchPaths) {
        long pid = NodeUtils.getPid(uniqueId);
        long vid = NodeUtils.getVendorId(uniqueId);
        LOGGER.info("Load the vendor cv definition for uniqueId: {}, pid: {}, vid: {}",
            NodeUtils.getUniqueIdAsString(uniqueId), pid, vid);

        // VendorCV vendorCV = null;
        Integer numberOfPorts = null;
        for (String searchPath : searchPaths) {
            StringBuffer filename = new StringBuffer("BiDiBCV-");
            filename.append(vid).append("-").append(pid).append(".xml");

            LOGGER.info("Prepared filename to load vendorCv: {}", filename.toString());
            if (searchPath.startsWith("classpath:")) {
                int beginIndex = "classpath:".length();
                String lookup = searchPath.substring(beginIndex) + "/" + filename.toString();
                LOGGER.info("Lookup vendorCv file internally: {}", lookup);
                InputStream is = VendorCvFactory.class.getResourceAsStream(lookup);
                if (is != null) {
                    numberOfPorts = getNumberOfPorts(is, portType);
                    if (numberOfPorts != null) {
                        break;
                    }
                }
                else {
                    LOGGER.warn("Internal lookup for products file failed.");
                }
            }
            else {
                File productsFile = new File(searchPath, filename.toString());
                if (productsFile.exists()) {
                    LOGGER.info("Found product file: {}", productsFile.getAbsolutePath());
                    // try to load products
                    numberOfPorts = getNumberOfPorts(productsFile, portType);
                    if (numberOfPorts != null) {
                        break;
                    }
                }
                else {
                    LOGGER.info("File does not exist: {}", productsFile.getAbsolutePath());
                }
            }
        }
        LOGGER.trace("Loaded numberOfPorts: {}", numberOfPorts);
        return numberOfPorts;
    }

    public Integer getNumberOfPorts(InputStream is, String portType) {
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = null;
        Document xmlDocument = null;
        try {
            builder = builderFactory.newDocumentBuilder();

            xmlDocument = builder.parse(is);
        }
        catch (Exception e) {
            LOGGER.warn("Parse document failed.", e);
        }

        Integer numberOfPorts = null;
        XPath xPath = XPathFactory.newInstance().newXPath();
        String expression = "VendorCV/CVDefinition/Node/Node[@Template='" + portType + "']/@Count";
        try {
            String configuredPorts = xPath.compile(expression).evaluate(xmlDocument/* , XPathConstants.NODE */);
            LOGGER.info("Found requested node: {}", configuredPorts);
            numberOfPorts = Integer.valueOf(configuredPorts);
        }
        catch (XPathExpressionException e) {
            LOGGER.warn("Get number of port failed.", e);
        }

        return numberOfPorts;
    }

    public Integer getNumberOfPorts(File vendorCvFile, String portType) {
        Integer numberOfPorts = null;
        InputStream is;
        try {
            is = new FileInputStream(vendorCvFile);
            numberOfPorts = getNumberOfPorts(is, portType);
        }
        catch (FileNotFoundException ex) {
            LOGGER.info("No vendorCV file found.");
        }
        return numberOfPorts;
    }
}
