package io.sesam.dukemicroservice;

import static io.sesam.dukemicroservice.IncrementalLuceneDatabase.DELETED_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.DATASET_ID_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.GROUP_NO_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.ORIGINAL_ENTITY_ID_PROPERTY_NAME;
import static spark.Spark.get;
import static spark.Spark.halt;
import static spark.Spark.post;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import no.priv.garshol.duke.ConfigLoader;
import no.priv.garshol.duke.Configuration;
import no.priv.garshol.duke.ConfigurationImpl;
import no.priv.garshol.duke.DataSource;
import no.priv.garshol.duke.JDBCLinkDatabase;
import no.priv.garshol.duke.Link;
import no.priv.garshol.duke.LinkDatabase;
import no.priv.garshol.duke.LinkStatus;
import no.priv.garshol.duke.Processor;
import no.priv.garshol.duke.Property;
import no.priv.garshol.duke.PropertyImpl;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.RecordIterator;
import no.priv.garshol.duke.datasources.Column;
import spark.ModelAndView;
import spark.Request;
import spark.Response;
import spark.template.jinjava.JinjavaEngine;

public class App {

    private static final String MIN_RELEVANCE = "MIN_RELEVANCE";
    private static final String FUZZY_SEARCH = "FUZZY_SEARCH";
    private static final String MAX_SEARCH_HITS = "MAX_SEARCH_HITS";
    private String configAsString;
    private File tempFolder;
    private File tempFolderFile;
    Gson gson = new Gson();

    private class RecordLinkage {
        final private String name;
        final boolean doOneToOneLinking;
        final Map<String, IncrementalRecordLinkageDataSource> dataSetId2dataSource;
        final IncrementalRecordLinkageMatchListener matchListener;
        final Processor processor;
        final Configuration config;
        private final LinkDatabase linkDatabase;
        private final IncrementalRecordLinkageLuceneDatabase luceneDatabase;
        final Lock lock = new ReentrantLock();

        RecordLinkage(String recordLinkageName,
                      String linkMode, Map<String, IncrementalRecordLinkageDataSource> dataSetId2dataSource,
                      IncrementalRecordLinkageMatchListener matchListener, Processor processor,
                      Configuration config,
                      LinkDatabase linkDatabase,
                      IncrementalRecordLinkageLuceneDatabase luceneDatabase
                      ) {
            this.name = recordLinkageName;
            this.dataSetId2dataSource = dataSetId2dataSource;
            this.matchListener = matchListener;
            this.processor = processor;
            this.config = config;
            this.linkDatabase = linkDatabase;
            this.luceneDatabase = luceneDatabase;

            switch(linkMode) {
                case "one-to-one":
                    this.doOneToOneLinking = true;
                    break;
                default:
                    throw new RuntimeException(String.format("Invalid link-mode '%s' specified for the '%s' recordlinkage.",
                                                             linkMode, recordLinkageName));
            }
        }

        @SuppressWarnings("unused")  // used by the html templates
        public String getName() {
            return name;
        }

        @SuppressWarnings("unused")  // used by the html templates
        public Map<String, IncrementalRecordLinkageDataSource> getDataSources() {
            return dataSetId2dataSource;
        }

    }

    private Map<String, RecordLinkage> recordLinkages = new HashMap<>();


    private class Deduplication {
        private final String name;
        private final Map<String, IncrementalDeduplicationDataSource> dataSetId2dataSource;
        final IncrementalDeduplicationMatchListener matchListener;
        final Processor processor;
        final Configuration config;
        final IncrementalDeduplicationLuceneDatabase luceneDatabase;
        final Lock lock = new ReentrantLock();
        final LinkDatabase linkDatabase;

        Deduplication(String deduplicationName,
                      Map<String, IncrementalDeduplicationDataSource> dataSetId2dataSource,
                      IncrementalDeduplicationMatchListener matchListener,
                      LinkDatabase linkDatabase,
                      Processor processor,
                      Configuration config,
                      IncrementalDeduplicationLuceneDatabase luceneDatabase
                      ) {
            this.name = deduplicationName;
            this.dataSetId2dataSource = dataSetId2dataSource;
            this.matchListener = matchListener;
            this.linkDatabase = linkDatabase;
            this.processor = processor;
            this.config = config;

            this.luceneDatabase = luceneDatabase;
        }

        @SuppressWarnings("unused")  // used by the html templates
        public String getName() {
            return name;
        }

        @SuppressWarnings("unused")  // used by the html templates
        public Map<String, IncrementalDeduplicationDataSource> getDataSources() {
            return dataSetId2dataSource;
        }
    }

    private Map<String, Deduplication> deduplications = new HashMap<>();

    private Logger logger = LoggerFactory.getLogger(App.class);

    @SuppressWarnings("unused")  // used by the html templates
    public Map<String, Deduplication> getDeduplications() {
        return deduplications;
    }

    @SuppressWarnings("unused")  // used by the html templates
    public Map<String, RecordLinkage> getRecordLinkages() {
        return recordLinkages;
    }

    public App() throws Exception {
        // Create the Duke processor

        Path atempFolderFile = Files.createTempDirectory(null);
        tempFolder = atempFolderFile.toFile();
        tempFolder.deleteOnExit();


        // TODO: load the config from an environment variable or something.
        final String CONFIG_STRING_ENVIRONMENT_VARIABLE_NAME = "CONFIG_STRING";
        String configFromEnvironmentVariable = System.getenv(CONFIG_STRING_ENVIRONMENT_VARIABLE_NAME);

        if (configFromEnvironmentVariable != null && !configFromEnvironmentVariable.isEmpty()) {
            logger.info("The {} environment variable contains a string, so I'll try to load the config from that.",
                        CONFIG_STRING_ENVIRONMENT_VARIABLE_NAME);

            Reader configFileReader = new StringReader(configFromEnvironmentVariable);
            parseConfigFile(configFileReader);

        } else {
            logger.info("The {} environment variable was empty, so I'll start up with a demo configuration.",
                        CONFIG_STRING_ENVIRONMENT_VARIABLE_NAME);
            String configFileName = "classpath:testdukeconfig.xml";
            Reader configFileReader;
            if (configFileName.startsWith("classpath:")) {
                String resource = configFileName.substring("classpath:".length());
                ClassLoader cloader = Thread.currentThread().getContextClassLoader();
                InputStream istream = cloader.getResourceAsStream(resource);
                configFileReader = new InputStreamReader(istream);
            } else {
                configFileReader = new FileReader(configFileName);
            }
            parseConfigFile(configFileReader);
        }
    }

    private void parseConfigFile(Reader configFileReader) throws XPathExpressionException, TransformerException, IOException, SAXException {
        logger.info("Parsing the config-file...");

        // Load thread config
        int numberOfThreads = 1;
        String numberOfThreadsConfig = System.getenv("THREADS");
        if (numberOfThreadsConfig != null && numberOfThreadsConfig.matches("\\d+")) {
            numberOfThreads = Integer.parseInt(numberOfThreadsConfig);
        }
        logger.info("Running with " + numberOfThreads + " threads");

        // Load thread config
        boolean profilePerformance = false;
        String profilePerformanceConfig = System.getenv("PROFILE");
        if (profilePerformanceConfig != null && profilePerformanceConfig.equals("1")) {
            profilePerformance = true;
            logger.warn("Running with profiling enabled");
        }


        // Store the config as a string, so that we can return it in the "/config" endpoint.
        char buffer[] = new char[10000];
        StringBuilder stringBuilder = new StringBuilder();
        int numCharsRead;
        while ((numCharsRead = configFileReader.read(buffer, 0, buffer.length)) != -1) {
            stringBuilder.append(buffer, 0, numCharsRead);
        }
        String newConfigAsString = stringBuilder.toString();

        configFileReader = new StringReader(newConfigAsString);

        XPath xPath = XPathFactory.newInstance().newXPath();

        // TODO: Do config-file validation with a schema or something.

        InputSource xml = new InputSource(configFileReader);

        NodeList dukeMicroServiceNodes = (NodeList) xPath.evaluate("//DukeMicroService", xml, XPathConstants.NODESET);
        if (dukeMicroServiceNodes.getLength() == 0) {
            throw new RuntimeException("The configfile didn't contain a 'DukeMicroService' entity!");
        }
        if (dukeMicroServiceNodes.getLength() > 1) {
            throw new RuntimeException("The configfile contain more than one 'DukeMicroService' entity!");
        }
        Node dukeMicroServiceNode = dukeMicroServiceNodes.item(0);

        Path rootDataFolder = Paths.get("").toAbsolutePath().resolve("data");

        Node dataFolderAttribute = dukeMicroServiceNode.getAttributes().getNamedItem("dataFolder");
        if (dataFolderAttribute != null) {
            String dataFolderFromConfig = dataFolderAttribute.getTextContent();
            if ((dataFolderFromConfig != null) && !dataFolderFromConfig.isEmpty()) {
                rootDataFolder = Paths.get(dataFolderFromConfig);
            }
        }

        Map<String, Deduplication> newDeduplications = new HashMap<>();
        Map<String, RecordLinkage> newRecordLinkages = new HashMap<>();
        for (int dukeChildNodeIndex = 0; dukeChildNodeIndex < dukeMicroServiceNode.getChildNodes().getLength(); dukeChildNodeIndex++) {
            Node dukeChildNode = dukeMicroServiceNode.getChildNodes().item(dukeChildNodeIndex);
            if (dukeChildNode instanceof Element) {
                Element element = (Element)dukeChildNode;

                String tagName = element.getTagName();
                switch (tagName) {
                    case "Deduplication": {
                        String deduplicationName = element.getAttributes().getNamedItem("name").getTextContent();

                        Path deduplicationDataFolder = rootDataFolder.resolve("deduplication").resolve(deduplicationName);

                        logger.info("  Parsing the config for the '{}' deduplication.", deduplicationName);
                        ConfigurationImpl config = parseDukeConfig("deduplication'" + deduplicationName + "'",
                                                                   element);

                        // Add the special "id", "datasetId" properties.
                        List<Property> properties = config.getProperties();
                        for (Property property : properties) {
                            if (property.isIdProperty()) {
                                throw new RuntimeException(String.format("    The schema contained an 'id'-property: '%s'", property.getName()));
                            }
                        }

                        PropertyImpl IdProperty = new PropertyImpl("ID");
                        assert IdProperty.isIdProperty();
                        properties.add(IdProperty);

                        PropertyImpl datasetIdProperty = new PropertyImpl(DATASET_ID_PROPERTY_NAME, null, 0, 0);
                        datasetIdProperty.setIgnoreProperty(true);
                        properties.add(datasetIdProperty);

                        PropertyImpl originalEntityIdProperty = new PropertyImpl(ORIGINAL_ENTITY_ID_PROPERTY_NAME, null, 0, 0);
                        originalEntityIdProperty.setIgnoreProperty(true);
                        properties.add(originalEntityIdProperty);

                        PropertyImpl deletedProperty = new PropertyImpl(DELETED_PROPERTY_NAME, null, 0, 0);
                        deletedProperty.setIgnoreProperty(true);
                        properties.add(deletedProperty);

                        config.setProperties(properties);

                        logger.info("    Created the Duke config for the deduplication '{}' ok.", deduplicationName);

                        IncrementalDeduplicationLuceneDatabase luceneDatabase = new IncrementalDeduplicationLuceneDatabase();
                        configureDatabase(luceneDatabase);
                        Path luceneFolderPath = deduplicationDataFolder.resolve("lucene-index");
                        File luceneFolderFile = luceneFolderPath.toFile();
                        boolean wasCreated = luceneFolderFile.mkdirs();
                        if (wasCreated) {
                            logger.info("    Created the folder '{}'.", luceneFolderPath.toString());
                        }
                        if (!luceneFolderFile.exists()) {
                            throw new RuntimeException(String.format("Failed to create the folder '%s'!", luceneFolderPath.toString()));
                        }
                        logger.info("    Using this folder for the lucene index: '{}'.", luceneFolderPath.toString());
                        luceneDatabase.setPath(luceneFolderPath.toString());
                        config.setDatabase(luceneDatabase);
                        Processor processor = new Processor(config, false);
                        processor.setThreads(numberOfThreads);
                        processor.setPerformanceProfiling(profilePerformance);

                        LinkDatabase linkDatabase = createLinkDatabase(element, deduplicationDataFolder, false);
                        
                        IncrementalDeduplicationMatchListener incrementalDeduplicationMatchListener  = new IncrementalDeduplicationMatchListener(
                                deduplicationName,
                                config,
                                linkDatabase
                                );
                        processor.addMatchListener(incrementalDeduplicationMatchListener);

                        // Load the datasources
                        Map<String, IncrementalDeduplicationDataSource> dataSetId2dataSource = new HashMap<>();
                        Collection<DataSource> dataSources = config.getDataSources();

                        if (dataSources.isEmpty()) {
                            throw new RuntimeException(
                                    String.format(
                                            "Got zero datasources in the deduplication '%s'!",
                                            deduplicationName));
                        }

                        for (DataSource dataSource : dataSources) {
                            if (dataSource instanceof IncrementalDeduplicationDataSource) {
                                IncrementalDeduplicationDataSource incrementalDeduplicationDataSource=
                                        (IncrementalDeduplicationDataSource) dataSource;
                                String datasetId = incrementalDeduplicationDataSource.getDatasetId();
                                if (datasetId == null || datasetId.isEmpty()) {
                                    throw new RuntimeException(
                                            String.format("Got a DataSource with no datasetId property in the deduplication '%s'!",
                                                          deduplicationName));
                                }

                                for (Column column : incrementalDeduplicationDataSource.getColumns()) {
                                    if (column.getName().toLowerCase().equals("_id") || column.getName().toLowerCase().equals("id")) {
                                        throw new RuntimeException(
                                                String.format("The DataSource '%s' in the deduplication '%s' contained an '%s' column!!",
                                                              datasetId, deduplicationName, column.getName()));
                                    }
                                }

                                dataSetId2dataSource.put(datasetId, incrementalDeduplicationDataSource);
                                logger.info("    Added the datasource '{}'", datasetId);
                            } else {
                                throw new RuntimeException(
                                        String.format("Got a DataSource of the unsupported type '%s' in the deduplication '%s'!",
                                                      dataSource.getClass().getName(), deduplicationName));
                            }

                        }

                        newDeduplications.put(deduplicationName, new Deduplication(deduplicationName,
                                                                                dataSetId2dataSource,
                                                                                incrementalDeduplicationMatchListener,
                                                                                linkDatabase,
                                                                                processor,
                                                                                config,
                                                                                luceneDatabase));

                    }
                    break;

                    case "RecordLinkage": {
                        String recordLinkageName = element.getAttributes().getNamedItem("name").getTextContent();
                        logger.info("  Parsing the config for the '{}' recordLinkage.", recordLinkageName);

                        String linkMode = element.getAttributes().getNamedItem("link-mode").getTextContent();

                        Path recordLinkDataFolder = rootDataFolder.resolve("recordLinkage").resolve(recordLinkageName);

                        ConfigurationImpl config = parseDukeConfig("recordLinkage '" + recordLinkageName + "'",
                                                                   element);

                        // Add the special "id", "datasetId" and "groupNo" properties.
                        List<Property> properties = config.getProperties();
                        for (Property property : properties) {
                            if (property.isIdProperty()) {
                                throw new RuntimeException(String.format("    The schema contained an 'id'-property: '%s'", property.getName()));
                            }
                        }

                        PropertyImpl IdProperty = new PropertyImpl("ID");
                        assert IdProperty.isIdProperty();
                        properties.add(IdProperty);

                        PropertyImpl datasetIdProperty = new PropertyImpl(DATASET_ID_PROPERTY_NAME, null, 0, 0);
                        datasetIdProperty.setIgnoreProperty(true);
                        properties.add(datasetIdProperty);

                        PropertyImpl originalEntityIdProperty = new PropertyImpl(ORIGINAL_ENTITY_ID_PROPERTY_NAME, null, 0, 0);
                        originalEntityIdProperty.setIgnoreProperty(true);
                        properties.add(originalEntityIdProperty);

                        PropertyImpl groupNoProperty = new PropertyImpl(GROUP_NO_PROPERTY_NAME, null, 0, 0);
                        groupNoProperty.setIgnoreProperty(true);
                        properties.add(groupNoProperty);

                        PropertyImpl deletedProperty = new PropertyImpl(DELETED_PROPERTY_NAME, null, 0, 0);
                        deletedProperty.setIgnoreProperty(true);
                        properties.add(deletedProperty);

                        config.setProperties(properties);

                        logger.info("    Created the Duke config for the recordLinkage '{}' ok.", recordLinkageName);

                        IncrementalRecordLinkageLuceneDatabase luceneDatabase = new IncrementalRecordLinkageLuceneDatabase();
                        configureDatabase(luceneDatabase);
                        Path luceneFolderPath = recordLinkDataFolder.resolve("lucene-index");
                        File luceneFolderFile = luceneFolderPath.toFile();
                        boolean wasCreated = luceneFolderFile.mkdirs();
                        if (wasCreated) {
                            logger.info("    Created the folder '{}'.", luceneFolderPath.toString());
                        }
                        if (!luceneFolderFile.exists()) {
                            throw new RuntimeException(String.format("Failed to create the folder '%s'!", luceneFolderPath.toString()));
                        }
                        logger.info("    Using this folder for the lucene index: '{}'.", luceneFolderPath.toString());
                        luceneDatabase.setPath(luceneFolderPath.toString());
                        config.setDatabase(luceneDatabase);
                        Processor processor = new Processor(config, false);
                        processor.setThreads(numberOfThreads);
                        processor.setPerformanceProfiling(profilePerformance);

                        LinkDatabase linkDatabase = createLinkDatabase(element, recordLinkDataFolder, true);

                        IncrementalRecordLinkageMatchListener incrementalRecordLinkageMatchListener = new IncrementalRecordLinkageMatchListener(
                                recordLinkageName,
                                config,
                                linkDatabase
                        );

                        processor.addMatchListener(incrementalRecordLinkageMatchListener);

                        // Load the datasources for the two groups
                        Map<String, IncrementalRecordLinkageDataSource> dataSetId2dataSource = new HashMap<>();
                        java.util.function.Consumer<Integer> addDataSourcesForGroup = (Integer groupNo) ->
                        {
                            Collection<DataSource> dataSources = config.getDataSources(groupNo);

                            if (dataSources.isEmpty()) {
                                throw new RuntimeException(
                                        String.format(
                                                "Got zero datasources for group %d in the recordLinkage '%s'!",
                                                groupNo,
                                                recordLinkageName));
                            }

                            for (DataSource dataSource : dataSources) {
                                if (dataSource instanceof IncrementalRecordLinkageDataSource) {
                                    IncrementalRecordLinkageDataSource incrementalRecordLinkageDataSource =
                                            (IncrementalRecordLinkageDataSource) dataSource;
                                    String datasetId = incrementalRecordLinkageDataSource.getDatasetId();
                                    if (datasetId == null || datasetId.isEmpty()) {
                                        throw new RuntimeException(
                                                String.format("Got a DataSource with no datasetId property in the recordLinkage '%s'!",
                                                              recordLinkageName));
                                    }

                                    for (Column column : incrementalRecordLinkageDataSource.getColumns()) {
                                        if (column.getName().toLowerCase().equals("_id") || column.getName().toLowerCase().equals("id")) {
                                            throw new RuntimeException(
                                                    String.format("The DataSource '%s' in the recordLinkage '%s' contained an '%s' column!!",
                                                                  datasetId, recordLinkageName, column.getName()));
                                        }
                                    }

                                    incrementalRecordLinkageDataSource.setGroupNo(groupNo);
                                    dataSetId2dataSource.put(datasetId, incrementalRecordLinkageDataSource);
                                    logger.info("    Added the datasource '{}' to group {}", datasetId, groupNo);
                                } else {
                                    throw new RuntimeException(
                                            String.format("Got a DataSource of the unsupported type '%s' in the recordLinkage '%s'!",
                                                          dataSource.getClass().getName(), recordLinkageName));
                                }
                            }
                        };
                        addDataSourcesForGroup.accept(1);
                        addDataSourcesForGroup.accept(2);

                        newRecordLinkages.put(recordLinkageName, new RecordLinkage(recordLinkageName,
                                                                                linkMode,
                                                                                dataSetId2dataSource,
                                                                                incrementalRecordLinkageMatchListener,
                                                                                processor,
                                                                                config,
                                                                                linkDatabase,
                                                                                luceneDatabase));
                        logger.info("This config database: {}", processor.getDatabase().toString());

                    }
                    break;

                    default:
                        throw new RuntimeException(String.format("Unknown element '%s' found in the configuration file!", tagName));
                }
            }
        }

        // The parsing succeeded, so make this config the new active config
        this.configAsString = newConfigAsString;
        this.deduplications = newDeduplications;
        this.recordLinkages = newRecordLinkages;
        logger.info("Done parsing the config-file. The config can be read from the '/config' endpoint with a GET-request, and updated with a PUT-request.");
    }

    private void configureDatabase(IncrementalLuceneDatabase luceneDatabase) {
        // moved out from database to make upgrades easier
        luceneDatabase.setMinRelevance(0.9f);
        luceneDatabase.setFuzzySearch(false);
        luceneDatabase.setMaxSearchHits(10);
        if (System.getenv(MIN_RELEVANCE) != null) {
            luceneDatabase.setMinRelevance(Float.parseFloat(System.getenv(MIN_RELEVANCE)));
        }
        if (System.getenv(FUZZY_SEARCH) != null) {
            luceneDatabase.setFuzzySearch(Boolean.parseBoolean(System.getenv(FUZZY_SEARCH)));
        }
        if (System.getenv(MAX_SEARCH_HITS) != null) {
            luceneDatabase.setMaxSearchHits(Integer.parseInt(System.getenv(MAX_SEARCH_HITS)));
        }
    }

    private LinkDatabase createLinkDatabase(Element element, Path dataFolder, boolean isRecordLinkage) {
        String linkDatabaseType = element.getAttribute("link-database-type");
        if (linkDatabaseType == null || linkDatabaseType.isEmpty()) {
            linkDatabaseType = "h2";
        }
        LinkDatabase linkDatabase;
        switch (linkDatabaseType) {
            case "in-memory":
                linkDatabase = new SinceAwareInMemoryLinkDatabase();
                break;
                
            case "h2": 
                {
                    Path h2DatabasePath;
                    if (isRecordLinkage) {
                        h2DatabasePath = dataFolder.resolve("recordlinkdatabase");
                    } else {
                        h2DatabasePath = dataFolder.resolve("linkdatabase");
                    }
                    
                    File h2DatabaseFolder = h2DatabasePath.toFile().getParentFile();
                    boolean wasCreated = h2DatabaseFolder.mkdirs();
                    if (wasCreated) {
                        logger.info("    Created the folder '{}'.", h2DatabaseFolder.getAbsolutePath());
                    }
                    if (!h2DatabaseFolder.exists()) {
                        throw new RuntimeException(String.format("Failed to create the folder '%s'!", h2DatabasePath.toString()));
                    }
        
                    logger.info("    Using this folder for the h2 linkdatabase: '{}'.", h2DatabasePath.toString());

                    JDBCLinkDatabase jdbcLinkDatabase = new JDBCLinkDatabase("org.h2.Driver",
                                                                             "jdbc:h2://" + h2DatabasePath.toString(),
                                                                             "h2",
                                                                             null
                    );
                    jdbcLinkDatabase.init();
                    linkDatabase = jdbcLinkDatabase;
                }
                break;
                
            default:
                throw new RuntimeException(String.format("Got an unknown 'link-database-type' value: '%s'", linkDatabaseType));
        }
        return linkDatabase;
    }

    private ConfigurationImpl parseDukeConfig(String parentElementLabel, Element element) throws IOException, TransformerException, SAXException {
        Node dukeConfigNode = null;
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node childNode = childNodes.item(i);
            if (childNode instanceof Element) {
                Element childElement = (Element) childNode;
                String childElementTagName = childElement.getTagName();
                if (childElementTagName.equals("duke")) {
                    dukeConfigNode = childNode;
                } else {
                    throw new RuntimeException(String.format("Unknown element '%s' found in the %s!", childElementTagName, parentElementLabel));
                }
            }
        }
        if (dukeConfigNode == null) {
            throw new RuntimeException(String.format("The %s didn't contain a <duke> element!", parentElementLabel));
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        File dukeConfigFile = File.createTempFile("dukeconfig_", ".xml");
        ConfigurationImpl config;
        try {
            FileOutputStream resultStream = new FileOutputStream(dukeConfigFile);
            StreamResult result = new StreamResult(resultStream);
            transformer.transform(new DOMSource(dukeConfigNode), result);
            resultStream.close();

            config = (ConfigurationImpl) ConfigLoader.load(dukeConfigFile.getAbsolutePath());
        } finally {
            dukeConfigFile.delete();
        }
        return config;
    }

    public static void main(String[] args) throws Exception {
        App app = new App();
        Gson gson = new Gson();

        get("/", (request, response) -> {
            Map<String, Object> model = new HashMap<>();
            model.put("hello", "Velocity World");
            model.put("app", app);
            return new JinjavaEngine().render(
                    new ModelAndView(model, "templates/index.html")
            );
        });

        get("/config", (Request req, Response res) -> {
            res.status(200);
            res.type("application/xml");
            Writer writer = res.raw().getWriter();
            writer.append(app.configAsString);
            writer.flush();
            return "";
        });

        post("/config", (Request req, Response res) -> {

            MultipartConfigElement multipartConfigElement = new MultipartConfigElement("/tmp");
            //req.raw().setAttribute("org.eclipse.multipartConfig", multipartConfigElement);

            req.raw().setAttribute("org.eclipse.jetty.multipartConfig", multipartConfigElement);

            Part uploadedFile = req.raw().getPart("configfile");
            final InputStream in = uploadedFile.getInputStream();
            app.parseConfigFile(new InputStreamReader(in));

            res.redirect("/");
            res.type("text/plain");
            return "ok";
        });


        get("/recordlinkage/:recordLinkage/:datasetId", app::getRecordlinkage);

        get("/recordlinkage/:recordLinkage/:datasetId/httptransform", app::getRecordlinkage);

        /*
         * This endpoint is used to do record linkage. The results can be read from the
         * GET /recordlinkage/:recordLinkage endpoint.
         */
        post("/recordlinkage/:recordLinkage/:datasetId", app::postRecordlinkage);

        /*
         * This endpoint is used to do record linkage as a http transform. The results are returned
         * in the http response.
         */
        post("/recordlinkage/:recordLinkage/:datasetId/httptransform", app::postRecordlinkageHttpTransform);

        get("/recordlinkage/:recordLinkage", (req, res) -> {
            try {
                String recordLinkageName = req.params("recordLinkage");
                if (recordLinkageName.isEmpty()) {
                    halt(400, "The recordLinkageName cannot be an empty string!");
                }

                RecordLinkage recordLinkage = app.recordLinkages.get(recordLinkageName);
                IncrementalRecordLinkageMatchListener incrementalRecordLinkageMatchListener = recordLinkage.matchListener;
                if (incrementalRecordLinkageMatchListener == null) {
                    halt(400, String.format("Unknown recordLinkage '%s'! (All recordLinkages must be specified in the configuration)",
                                            recordLinkageName));
                }

                if (!recordLinkage.lock.tryLock(1000, TimeUnit.MILLISECONDS)) {
                    // failed to get the log
                    res.status(503);
                    res.type("text/plain");
                    Writer writer = res.raw().getWriter();
                    writer.append("The recordLinkage is being written to, so reading is not currently possible. Please wait a bit and try again later.");
                    writer.flush();
                    return "";
                } else {
                    try {
                        IncrementalRecordLinkageLuceneDatabase luceneDatabase = recordLinkage.luceneDatabase;
                        String sinceAsString = req.queryParams("since");
                        long since = 0;
                        if (sinceAsString != null && !sinceAsString.isEmpty()) {
                            since = Long.parseLong(sinceAsString);
                        }

                        res.status(200);
                        res.type("application/json");
                        Writer writer = res.raw().getWriter();

                        writer.append("[");
                        boolean isFirstEntity = true;

                        Collection<Link> links = recordLinkage.linkDatabase.getChangesSince(since);

                        for (Link link : links) {

                            if (isFirstEntity) {
                                isFirstEntity = false;
                            } else {
                                writer.append(",\n");
                            }

                            String record1Id = link.getID1();
                            String record2Id = link.getID2();

                            Record record1 = luceneDatabase.findRecordById(record1Id);
                            Record record2 = luceneDatabase.findRecordById(record2Id);

                            JsonObject entity = new JsonObject();
                            entity.addProperty("_id", (link.getID1() + "_" + link.getID2()).replace(':', '_'));
                            entity.addProperty("_updated", link.getTimestamp());
                            entity.addProperty("_deleted", link.getStatus().equals(LinkStatus.RETRACTED));
                            entity.addProperty("entity1", record1 != null ? record1.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME) : null);
                            entity.addProperty("entity2", record2 != null ? record2.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME) : null);
                            entity.addProperty("dataset1", record1 != null ? record1.getValue(DATASET_ID_PROPERTY_NAME) : null);
                            entity.addProperty("dataset2", record2 != null ? record2.getValue(DATASET_ID_PROPERTY_NAME) : null);
                            entity.addProperty("confidence", link.getConfidence());

                            String entityLinkAsString = entity.toString();
                            writer.append(entityLinkAsString);
                        }

                        writer.append("]");

                        writer.flush();
                        return "";
                    } finally {
                        recordLinkage.lock.unlock();
                    }
                }
            } catch (Exception e) {
                if (e.getCause() instanceof org.eclipse.jetty.io.EofException) {
                    app.logger.info("Ignoring a EofException when serving GET '" + req.url() + ".");
                } else {
                    throw e;
                }
            }
            return "";
        });

        get("/deduplication/:deduplication/:datasetId", app::getDeduplicationDatasetId);

        get("/deduplication/:deduplication/:datasetId/httptransform", app::getDeduplicationDatasetId);

        /*
         * This endpoint is used to do deduplication. The results can be read from the
         * GET /deduplication/:deduplication endpoint.
         */
        post("/deduplication/:deduplication/:datasetId", app::postDeduplication);

        /*
         * This endpoint is used to do deduplication in a http transform. The results can be read from the
         * http response.
         */
        post("/deduplication/:deduplication/:datasetId/httptransform", app::postDeduplicationHttpTransform);

        get("/deduplication/:deduplication", (req, res) -> {
            try {
                String deduplicationName = req.params("deduplication");
                if (deduplicationName.isEmpty()) {
                    halt(400, "The deduplicationName cannot be an empty string!");
                }

                Deduplication deduplication = app.deduplications.get(deduplicationName);
                if (deduplication == null) {
                    halt(400, String.format("Unknown deduplication '%s'! (All deduplications must be specified in the configuration)",
                                            deduplicationName));
                }

                IncrementalDeduplicationLuceneDatabase luceneDatabase = deduplication.luceneDatabase;

                String sinceAsString = req.queryParams("since");
                long since = 0;
                if (sinceAsString != null && !sinceAsString.isEmpty()) {
                    since = Long.parseLong(sinceAsString);
                }

                if (!deduplication.lock.tryLock(1000, TimeUnit.MILLISECONDS)) {
                    // failed to get the log
                    res.status(503);
                    res.type("text/plain");
                    Writer writer = res.raw().getWriter();
                    writer.append("The deduplication is being written to, so reading is not currently possible. Please wait a bit and try again later.");
                    writer.flush();
                    return "";
                } else {
                    try {
                        res.status(200);
                        res.type("application/json");
                        Writer writer = res.raw().getWriter();

                        writer.append("[");
                        boolean isFirstEntity = true;
                        for (Link link : deduplication.linkDatabase.getChangesSince(since)) {

                            if (isFirstEntity) {
                                isFirstEntity = false;
                            } else {
                                writer.append(",\n");
                            }

                            String record1Id = link.getID1();
                            String record2Id = link.getID2();

                            Record record1 = luceneDatabase.findRecordById(record1Id);
                            Record record2 = luceneDatabase.findRecordById(record2Id);

                            JsonObject entity = new JsonObject();
                            entity.addProperty("_id", (link.getID1() + "_" + link.getID2()).replace(':', '_'));
                            entity.addProperty("_updated", link.getTimestamp());
                            entity.addProperty("_deleted", link.getStatus().equals(LinkStatus.RETRACTED));
                            entity.addProperty("entity1", record1 != null ? record1.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME) : null);
                            entity.addProperty("entity2", record2 != null ? record2.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME) : null);
                            entity.addProperty("dataset1", record1 != null ? record1.getValue(DATASET_ID_PROPERTY_NAME) : null);
                            entity.addProperty("dataset2", record2 != null ? record2.getValue(DATASET_ID_PROPERTY_NAME) : null);
                            entity.addProperty("confidence", link.getConfidence());

                            String entityLinkAsString = entity.toString();
                            writer.append(entityLinkAsString);
                        }

                        writer.append("]");
                        writer.flush();
                        return "";
                    } finally {
                        deduplication.lock.unlock();
                    }
                }
            } catch (Exception e) {
                if (e.getCause() instanceof org.eclipse.jetty.io.EofException) {
                    app.logger.info("Ignoring a EofException when serving GET '" + req.url() + ".");
                } else {
                    throw e;
                }
            }
            return "";
        });
    }

    private Object getDeduplicationDatasetId(Request req, Response res) {
        String deduplicationName = req.params("deduplication");
        String datasetId = req.params("datasetId");

        if (deduplicationName.isEmpty()) {
            halt(404, "The deduplicationName cannot be an empty string!");
        }

        if (datasetId.isEmpty()) {
            halt(404, "The datasetId cannot be an empty string!");
        }

        Deduplication deduplication = this.deduplications.get(deduplicationName);
        if (deduplication == null) {
            halt(404, String.format("Unknown deduplication '%s'! (All deduplications must be specified in the configuration)",
                                    deduplicationName));
        }

        IncrementalDeduplicationDataSource dataSource = deduplication.getDataSources().get(datasetId);
        if (dataSource == null) {
            halt(404, String.format("Unknown dataset-id '%s' for the deduplication '%s'!", datasetId, deduplicationName));
        }

        halt(405, "This endpoint only supports POST requests.");
        return "";
    }

    private Object postDeduplicationHttpTransform(Request req, Response res) throws IOException {
        return internalPostDeduplication(req, res, true);
    }

    private Object postDeduplication(Request req, Response res) throws IOException {
        return internalPostDeduplication(req, res, false);
    }

    private Object internalPostDeduplication(Request req, Response res, boolean isHttpTransform) throws IOException {
        String deduplicationName = req.params("deduplication");
        String datasetId = req.params("datasetId");

        if (deduplicationName.isEmpty()) {
            halt(404, "The deduplicationName cannot be an empty string!");
        }

        if (datasetId.isEmpty()) {
            halt(404, "The datasetId cannot be an empty string!");
        }

        Deduplication deduplication = this.deduplications.get(deduplicationName);
        if (deduplication == null) {
            halt(404, String.format("Unknown deduplication '%s'! (All deduplications must be specified in the configuration)",
                                    deduplicationName));
        }

        IncrementalDeduplicationDataSource dataSource = deduplication.getDataSources().get(datasetId);
        if (dataSource == null) {
            halt(404, String.format("Unknown dataset-id '%s' for the deduplication '%s'!", datasetId, deduplicationName));
        }

        deduplication.lock.lock();
        try {

            res.type("application/json");

            JsonArray elementsInBatch;
            boolean requestContainedSingleEntity;
            // We assume that we get the records in small batches, so that it is ok to load the entire request into memory.
            String requestBody = req.body();
            try {
                elementsInBatch = gson.fromJson(requestBody, JsonArray.class);
                requestContainedSingleEntity = false;
            } catch (JsonSyntaxException e) {
                // The request can contain either an array of entities, or one single entity.
                JsonObject singleElement = gson.fromJson(requestBody, JsonObject.class);
                elementsInBatch = new JsonArray();
                elementsInBatch.add(singleElement);
                requestContainedSingleEntity = true;
            }

            // use the DataSource for the datasetId to convert the JsonObjects into Record objects.
            dataSource.setDatasetEntitiesBatch(elementsInBatch);
            RecordIterator it = dataSource.getRecords();
            List<Record> records = new LinkedList<>();
            List<Record> deletedRecords = new LinkedList<>();
            while (it.hasNext()) {
                Record record = it.next();
                if ("true".equals(record.getValue("_deleted"))) {
                    deletedRecords.add(record);
                } else {
                    records.add(record);
                }
            }

            Processor processor = deduplication.processor;
            IncrementalDeduplicationLuceneDatabase database = deduplication.luceneDatabase;

            IncrementalDeduplicationMatchListener incrementalDeduplicationMatchListener = deduplication.matchListener;
            LinkDatabase linkDatabase = deduplication.linkDatabase;

            try {
                for (Record record : deletedRecords) {
                    String recordId = record.getValue("ID");

                    // 1. mark the record as deleted in the lucene index. We cant just delete it alltogether, since we
                    //    need to do a lookup in lucine in the GET-handler (since the LinkDatabase doesn't contain all
                    //    the information we need)
                    database.index(record);

                    // retract all links for this record
                    for (Link link : linkDatabase.getAllLinksFor(recordId)) {
                        link.retract();
                        linkDatabase.assertLink(link);
                    }
                }

                // Process the actual records in the batch.
                if (!records.isEmpty()) {
                    processor.deduplicate(records);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            res.status(200);
            res.type("application/json");
            Writer writer = res.raw().getWriter();
            if (isHttpTransform) {
                writeHttpTransformResponse(elementsInBatch,
                                           requestContainedSingleEntity,
                                           incrementalDeduplicationMatchListener,
                                           writer
                                           );
            } else {
                writer.append("{\"success\": true}");
            }
            writer.flush();
            return "";
        } finally {
            deduplication.lock.unlock();
        }
    }

    private Object getRecordlinkage(Request req, Response res) throws IOException
    {
        String recordLinkageName = req.params("recordLinkage");
        String datasetId = req.params("datasetId");

        if (recordLinkageName.isEmpty()) {
            halt(404, "The recordLinkage cannot be an empty string!");
        }

        if (datasetId.isEmpty()) {
            halt(404, "The datasetId cannot be an empty string!");
        }

        RecordLinkage recordLinkage = this.recordLinkages.get(recordLinkageName);
        if (recordLinkage == null) {
            halt(404, String.format("Unknown recordLinkage '%s'! (All recordLinkages must be specified in the configuration)",
                                    recordLinkageName));
        }

        IncrementalRecordLinkageDataSource dataSource = recordLinkage.dataSetId2dataSource.get(datasetId);
        if (dataSource == null) {
            halt(404, String.format("Unknown dataset-id '%s' for the recordLinkage '%s'!", datasetId, recordLinkageName));
        }
        halt(405, "This endpoint only supports POST requests.");
        return "";
    }

    private Object postRecordlinkageHttpTransform(Request req, Response res) throws IOException {
        return internalPostRecordlinkage(req, res, true);
    }

    private Object postRecordlinkage(Request req, Response res) throws IOException {
        return internalPostRecordlinkage(req, res, false);
    }

    private Object internalPostRecordlinkage(Request req, Response res, boolean isHttpTransform) throws IOException {

        String recordLinkageName = req.params("recordLinkage");
        String datasetId = req.params("datasetId");

        if (recordLinkageName.isEmpty()) {
            halt(404, "The recordLinkage cannot be an empty string!");
        }

        if (datasetId.isEmpty()) {
            halt(404, "The datasetId cannot be an empty string!");
        }

        RecordLinkage recordLinkage = this.recordLinkages.get(recordLinkageName);
        if (recordLinkage == null) {
            halt(404, String.format("Unknown recordLinkage '%s'! (All recordLinkages must be specified in the configuration)",
                                    recordLinkageName));
        }

        IncrementalRecordLinkageDataSource dataSource = recordLinkage.dataSetId2dataSource.get(datasetId);
        if (dataSource == null) {
            halt(404, String.format("Unknown dataset-id '%s' for the recordLinkage '%s'!", datasetId, recordLinkageName));
        }

        Processor processor = recordLinkage.processor;
        IncrementalRecordLinkageLuceneDatabase database = (IncrementalRecordLinkageLuceneDatabase) processor.getDatabase();

        IncrementalRecordLinkageMatchListener incrementalRecordLinkageMatchListener = recordLinkage.matchListener;
        LinkDatabase linkDatabase = recordLinkage.linkDatabase;

        try {  // Use a try-finally to always release the recordLinkage.lock
            recordLinkage.lock.lock();
            database.setIndexingIsDisabled(false);

            res.type("application/json");

            JsonArray elementsInBatch;
            // We assume that we get the records in small batches, so that it is ok to load the entire request into memory.
            String requestBody = req.body();
            boolean requestContainedSingleEntity;
            try {
                elementsInBatch = gson.fromJson(requestBody, JsonArray.class);
                requestContainedSingleEntity = false;
            } catch (JsonSyntaxException e) {
                // The request can contain either an array of entities, or one single entity.
                JsonObject singleElement = gson.fromJson(requestBody, JsonObject.class);
                elementsInBatch = new JsonArray();
                elementsInBatch.add(singleElement);
                requestContainedSingleEntity = true;
            }

            // use the DataSource for the datasetId to convert the JsonObjects into Record objects.
            dataSource.setDatasetEntitiesBatch(elementsInBatch);
            RecordIterator it = dataSource.getRecords();
            List<Record> records = new LinkedList<>();
            List<Record> deletedRecords = new LinkedList<>();
            while (it.hasNext()) {
                Record record = it.next();
                if ("true".equals(record.getValue(DELETED_PROPERTY_NAME))) {
                    deletedRecords.add(record);
                } else {
                    records.add(record);
                }
            }

            if (isHttpTransform) {
                database.setIndexingIsDisabled(true);
                incrementalRecordLinkageMatchListener.setLinkDatabaseUpdatedIsDisabled(true);
            } else {
                // When we get a record with "_deleted"=True, we must do the following:
                // 1. Delete the record from the lucene index
                // 2. If the record appears in the RECORDLINKAGE table:
                //      Mark that row as "deleted=true".
                //      Mark the other record in that row as needing a rematching (add a row to the RECORDS_THAT_NEEDS_REMATCH table)
                // 3. If the record appears in the RECORDS_THAT_NEEDS_REMATCH table:
                //      Delete the row.
                for (Record record : deletedRecords) {
                    String recordId = record.getValue("ID");

                    // 1. mark the record as deleted in the lucene index. We cant just delete it alltogether, since we
                    //    need to do a lookup in lucine in the GET-handler (since the LinkDatabase doesn't contain all
                    //    the information we need)
                    database.index(record);

                    // retract all links for this record
                    for (Link link : linkDatabase.getAllLinksFor(recordId)) {
                        link.retract();
                        linkDatabase.assertLink(link);
                    }
                }
            }

            // Process the actual records in the batch.
            if (!records.isEmpty()) {
                processor.deduplicate(records);
            }

            Writer writer = res.raw().getWriter();
            if (isHttpTransform) {
                writeHttpTransformResponse(elementsInBatch,
                                           requestContainedSingleEntity,
                                           incrementalRecordLinkageMatchListener,
                                           writer
                                           );
            } else {
                writer.append("{\"success\": true}");
            }
            writer.flush();
            return "";
        } finally {
            database.setIndexingIsDisabled(false);
            incrementalRecordLinkageMatchListener.setLinkDatabaseUpdatedIsDisabled(false);
            recordLinkage.lock.unlock();
        }
    }

    private void writeHttpTransformResponse(JsonArray elementsInBatch,
                                            boolean requestContainedSingleEntity,
                                            BaseLinkDatabaseMatchListener matchListener,
                                            Writer writer) {
        JsonArray responseObjects = new JsonArray();
        for (JsonElement sourceElement: elementsInBatch) {
            JsonObject sourceObject = sourceElement.getAsJsonObject();
            JsonObject responseObject = gson.fromJson(gson.toJson(sourceObject),
                                                      sourceObject.getClass());

            JsonArray dukeLinks = matchListener.getLinksForObject(responseObject);

            responseObject.add("duke_links", dukeLinks);
            responseObjects.add(responseObject);
        }
        if (requestContainedSingleEntity && responseObjects.size() == 1) {
            gson.toJson(responseObjects.get(0), writer);
        } else {
            gson.toJson(responseObjects, writer);
        }

    }
}