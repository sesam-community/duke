package io.sesam.dukemicroservice;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;

import no.priv.garshol.duke.Comparator;
import no.priv.garshol.duke.Configuration;
import no.priv.garshol.duke.Database;
import no.priv.garshol.duke.DukeConfigException;
import no.priv.garshol.duke.DukeException;
import no.priv.garshol.duke.Property;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.comparators.GeopositionComparator;
import no.priv.garshol.duke.databases.DocumentRecord;
import no.priv.garshol.duke.databases.GeoProperty;
import no.priv.garshol.duke.utils.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/*
 * We need our own version of the LuceneDatabase class. We would have preferred to just derive from
 * the LuceneDatabase class, but that is not possible, since too many of the bits we need are
 * private in the LuceneDatabase class.
 */
public abstract class IncrementalLuceneDatabase implements Database {
    // START OF STUFF COPIED AS-IS FROM LuceneDatabase. If we update the Duke-library from v1.2 we must make a new
    // copy from LuceneDatabase.
    private Configuration config;
    private EstimateResultTracker maintracker;
    private IndexWriter iwriter;
    private Directory directory;
    private IndexReader reader;
    private IndexSearcher searcher;
    private Analyzer analyzer;
    // Deichman case:
    //  1 = 40 minutes
    //  4 = 48 minutes
    private final static int SEARCH_EXPANSION_FACTOR = 1;
    private int max_search_hits;
    private float min_relevance;
    private boolean overwrite;
    private String path;
    private boolean fuzzy_search;

    // helper for geostuff
    private GeoProperty geoprop;

    private final Logger logger;

    private boolean indexingIsDisabled = false;

    public IncrementalLuceneDatabase() {
        this.analyzer = new StandardAnalyzer(Version.LUCENE_CURRENT);
        this.maintracker = new EstimateResultTracker();
        this.logger = LoggerFactory.getLogger("IncrementalLuceneDatabase");
    }

    public void setConfiguration(Configuration config) {
        this.config = config;
        logger.info("Setting config: {}", config.getProperties());
    }

    public void setIndexingIsDisabled(boolean indexingIsDisabled) {
        this.indexingIsDisabled = indexingIsDisabled;
    }

    public void setOverwrite(boolean overwrite) {
        this.overwrite = overwrite;
    }

    public void setMaxSearchHits(int max_search_hits) {
        this.max_search_hits = max_search_hits;
    }

    public void setMinRelevance(float min_relevance) {
        this.min_relevance = min_relevance;
    }

    /**
     * Controls whether to use fuzzy searches for properties that have
     * fuzzy comparators. True by default.
     */
    public void setFuzzySearch(boolean fuzzy_search) {
        this.fuzzy_search = fuzzy_search;
    }

    /**
     * Returns the path to the Lucene index directory. If null, it means
     * the Lucene index is kept in-memory.
     */
    public String getPath() {
        return path;
    }

    /**
     * The path to the Lucene index directory. If null or not set, it
     * means the Lucene index is kept in-memory.
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Returns true iff the Lucene index is held in memory rather than
     * on disk.
     */
    public boolean isInMemory() {
        return (directory instanceof RAMDirectory);
    }

    /**
     * Flushes all changes to disk.
     */
    public void commit() {
        if (directory == null)
            return;

        try {
            if (reader != null)
                reader.close();

            // it turns out that IndexWriter.optimize actually slows
            // searches down, because it invalidates the cache. therefore
            // not calling it any more.
            // http://www.searchworkings.org/blog/-/blogs/uwe-says%3A-is-your-reader-atomic
            // iwriter.optimize();

            iwriter.commit();
            openSearchers();
        } catch (IOException e) {
            throw new DukeException(e);
        }
    }

    /**
     * Look up record by identity.
     */
    public Record findRecordById(String id) {
        if (directory == null)
            init();

        Property idprop = config.getIdentityProperties().iterator().next();
        for (Record r : lookup(idprop, id))
            if (r.getValue(idprop.getName()).equals(id))
                return r;

        return null; // not found
    }


    /**
     * Stores state to disk and closes all open resources.
     */
    public void close() {
        if (directory == null)
            return;

        try {
            iwriter.close();
            directory.close();
            if (reader != null)
                reader.close();
        } catch (IOException e) {
            throw new DukeException(e);
        }
    }

    public String toString() {
        return "LuceneDatabase, max-search-hits: " + max_search_hits +
                ", min-relevance: " + min_relevance + ", fuzzy=" + fuzzy_search +
                "\n  " + directory;
    }

    // ----- INTERNALS

    private void init() {
        try {
            openIndexes(overwrite);
            openSearchers();
            initSpatial();
        } catch (IOException e) {
            throw new DukeException(e);
        }
    }

    private void openIndexes(boolean overwrite) {
        if (directory == null) {
            try {
                if (path == null)
                    directory = new RAMDirectory();
                else {
                    //directory = new MMapDirectory(new File(config.getPath()));
                    // as per http://wiki.apache.org/lucene-java/ImproveSearchingSpeed
                    // we use NIOFSDirectory, provided we're not on Windows
                    if (Utils.isWindowsOS())
                        directory = FSDirectory.open(new File(path));
                    else
                        directory = NIOFSDirectory.open(new File(path));
                }

                IndexWriterConfig cfg =
                        new IndexWriterConfig(Version.LUCENE_CURRENT, analyzer);
                cfg.setOpenMode(overwrite ? IndexWriterConfig.OpenMode.CREATE :
                                        IndexWriterConfig.OpenMode.APPEND);
                iwriter = new IndexWriter(directory, cfg);
                iwriter.commit(); // so that the searcher doesn't fail
            } catch (IndexNotFoundException e) {
                if (!overwrite) {
                    // the index was not there, so make a new one
                    directory = null; // ensure we really do try again
                    openIndexes(true);
                } else
                    throw new DukeException(e);
            } catch (IOException e) {
                throw new DukeException(e);
            }
        }
    }

    public void openSearchers() throws IOException {
        reader = DirectoryReader.open(directory);
        searcher = new IndexSearcher(reader);
    }

    /**
     * Parses the query. Using this instead of a QueryParser in order
     * to avoid thread-safety issues with Lucene's query parser.
     *
     * @param fieldName the name of the field
     * @param value the value of the field
     * @return the parsed query
     */
    private Query parseTokens(String fieldName, String value) {
        BooleanQuery searchQuery = new BooleanQuery();
        if (value != null) {
            Analyzer analyzer = new KeywordAnalyzer();

            try {
                TokenStream tokenStream =
                        analyzer.tokenStream(fieldName, new StringReader(value));
                tokenStream.reset();
                CharTermAttribute attr =
                        tokenStream.getAttribute(CharTermAttribute.class);

                while (tokenStream.incrementToken()) {
                    String term = attr.toString();
                    Query termQuery = new TermQuery(new Term(fieldName, term));
                    searchQuery.add(termQuery, BooleanClause.Occur.SHOULD);
                }
            } catch (IOException e) {
                throw new DukeException("Error parsing input string '"+value+"' "+
                                                "in field " + fieldName);
            }
        }

        return searchQuery;
    }

    /**
     * Parses Lucene query.
     * @param required Iff true, return only records matching this value.
     */
    protected void parseTokens(BooleanQuery parent, String fieldName,
                               String value, boolean required) {
        value = escapeLucene(value);
        if (value.length() == 0)
            return;

        try {
            TokenStream tokenStream =
                    analyzer.tokenStream(fieldName, new StringReader(value));
            tokenStream.reset();
            CharTermAttribute attr =
                    tokenStream.getAttribute(CharTermAttribute.class);

            while (tokenStream.incrementToken()) {
                String term = attr.toString();
                Query termQuery;
                if (fuzzy_search && isFuzzy(fieldName))
                    termQuery = new FuzzyQuery(new Term(fieldName, term));
                else
                    termQuery = new TermQuery(new Term(fieldName, term));
                parent.add(termQuery, required ? BooleanClause.Occur.MUST : BooleanClause.Occur.SHOULD);
            }
        } catch (IOException e) {
            throw new DukeException("Error parsing input string '"+value+"' "+
                                            "in field " + fieldName);
        }
    }

    private boolean isFuzzy(String fieldName) {
        Comparator c = config.getPropertyByName(fieldName).getComparator();
        return c != null && c.isTokenized();
    }

    private String escapeLucene(String query) {
        char[] tmp = new char[query.length() * 2];
        int count = 0;
        for (int ix = 0; ix < query.length(); ix++) {
            char ch = query.charAt(ix);
            if (ch == '*' || ch == '?' || ch == '!' || ch == '&' || ch == '(' ||
                    ch == ')' || ch == '-' || ch == '+' || ch == ':' || ch == '"' ||
                    ch == '[' || ch == ']' || ch == '~' || ch == '{' || ch == '}' ||
                    ch == '^' || ch == '|')
                tmp[count++] = '\\'; // these characters must be escaped
            tmp[count++] = ch;
        }

        return new String(tmp, 0, count).trim();
    }

    public Collection<Record> lookup(Property property, String value) {
        Query query = parseTokens(property.getName(), value);
        return maintracker.doQuery(query);
    }

    /**
     * The tracker is used to estimate the size of the query result
     * we should ask Lucene for. This parameter is the single biggest
     * influence on search performance, but setting it too low causes
     * matches to be missed. We therefore try hard to estimate it as
     * correctly as possible.
     *
     * The tracker uses a ring buffer of recent result sizes to
     * estimate the result size.
     */
    class EstimateResultTracker {
        private int limit;
        /**
         * Ring buffer containing n last search result sizes, except for
         * searches which found nothing.
         */
        private int[] prevsizes;
        private int sizeix; // position in prevsizes

        public EstimateResultTracker() {
            this.limit = 100;
            this.prevsizes = new int[10];
        }

        public Collection<Record> doQuery(Query query) {
            return doQuery(query, null);
        }

        public Collection<Record> doQuery(Query query, Filter filter) {
            if (directory == null) {
                init();
            }

            List<Record> matches;
            try {
                ScoreDoc[] hits;

                int thislimit = Math.min(limit, max_search_hits);
                while (true) {
                    hits = searcher.search(query, filter, thislimit).scoreDocs;
                    if (hits.length < thislimit || thislimit == max_search_hits)
                        break;
                    thislimit = thislimit * 5;
                }

                matches = new ArrayList(Math.min(hits.length, max_search_hits));
                for (int ix = 0; ix < hits.length &&
                        hits[ix].score >= min_relevance; ix++)

                    matches.add(new DocumentRecord(hits[ix].doc,
                                                   searcher.doc(hits[ix].doc)));

                if (hits.length > 0) {
                    synchronized(this) {
                        prevsizes[sizeix++] = matches.size();
                        if (sizeix == prevsizes.length) {
                            sizeix = 0;
                            limit = Math.max((int) (average() * SEARCH_EXPANSION_FACTOR), limit);
                        }
                    }
                }
            } catch (IOException e) {
                throw new DukeException(e);
            }
            return matches;
        }

        private double average() {
            int sum = 0;
            int ix = 0;
            for (; ix < prevsizes.length && prevsizes[ix] != 0; ix++)
                sum += prevsizes[ix];
            return sum / (double) ix;
        }
    }

    /**
     * Checks to see if we need the spatial support, and if so creates
     * the necessary context objects.
     */
    private void initSpatial() {
        // FIXME: for now, we only use geosearch if that's the only way to
        // find suitable records, since we don't know how to combine
        // geosearch ranking with normal search ranking.
        if (config.getLookupProperties().size() != 1)
            return;

        Property prop = config.getLookupProperties().iterator().next();
        if (!(prop.getComparator() instanceof GeopositionComparator))
            return;

        geoprop = new GeoProperty(prop);
    }
    // END OF STUFF COPIED AS-IS FROM LuceneDatabase


    //***************************************************************************************************************************************
    // The rest of this class is new/modified methods.
    //***************************************************************************************************************************************

    static String GROUP_NO_PROPERTY_NAME = "dukeGroupNo";
    static String DATASET_ID_PROPERTY_NAME = "dukeDatasetId";
    static String ORIGINAL_ENTITY_ID_PROPERTY_NAME = "dukeOriginalEntityId";
    static String DELETED_PROPERTY_NAME = "dukeDeleted";



    /**
     * We implement this in order to be able to ignore all records from the same recordLinkage group
     */
    public Collection<Record> findCandidateMatches(Record record, boolean doGroupFiltering) {
        // if we have a geoprop it means that's the only way to search
        if (geoprop != null) {
            throw new RuntimeException("The GeopositionComparator has not been implemented in the Duke microservice yet.");
        }

        BooleanQuery query = new BooleanQuery();

        if (doGroupFiltering) {
            // Add the group filter (we only want records from other groups)
            String groupNo = record.getValue(GROUP_NO_PROPERTY_NAME);
            if (groupNo == null || groupNo.isEmpty()) {
                throw new RuntimeException(String.format("The '%s' property was missing or empty!", GROUP_NO_PROPERTY_NAME));
            }

            query.add(new TermQuery(new Term(GROUP_NO_PROPERTY_NAME, groupNo)), BooleanClause.Occur.MUST_NOT);
        }

        // skip deleted entities
        query.add(new TermQuery(new Term(DELETED_PROPERTY_NAME, "true")), BooleanClause.Occur.MUST_NOT);

        // Build the combined query for all lookup properties
        for (Property prop : config.getLookupProperties()) {
            Collection<String> values = record.getValues(prop.getName());
            if (values == null)
                continue;
            for (String value : values)
                parseTokens(query, prop.getName(), value,
                            prop.getLookupBehaviour() == Property.Lookup.REQUIRED);
        }

        // do the query
        return maintracker.doQuery(query);
    }


    /**
     * override this to store the DATASET_ID_PROPERTY_NAME and GROUP_NO_PROPERTY_NAME as Field.Index.NOT_ANALYZED
     */
    public void index(Record record) {
        if (indexingIsDisabled) {
            // when we are running a http transform, we sometimes don't wan't to index anything.
            return;
        }
        index(record, false);

    }

    public void index(Record record, boolean markAsDeleted) {
        if (indexingIsDisabled) {
            // when we are running a http transform, we sometimes don't wan't to index anything.
            return;
        }

        if (directory == null)
            init();

        if (!overwrite && path != null)
            delete(record);

        Document doc = new Document();
        for (String propname : record.getProperties()) {
            Property prop = config.getPropertyByName(propname);
            if (prop == null) {
                throw new DukeConfigException("Record has property " + propname +
                                                      " for which there is no configuration");
            }

            if (prop.getComparator() instanceof GeopositionComparator &&
                    geoprop != null) {
                // index specially as geocoordinates

                String v = record.getValue(propname);
                if (v == null || v.equals(""))
                    continue;

                // this gives us a searchable geoindexed value
                for (IndexableField f : geoprop.createIndexableFields(v))
                    doc.add(f);

                // this preserves the coordinates in readable form for display purposes
                doc.add(new Field(propname, v, Field.Store.YES,
                                  Field.Index.NOT_ANALYZED));
            } else {
                Field.Index ix;
                if (prop.isIdProperty()
                        || propname.equals(DATASET_ID_PROPERTY_NAME)
                        || propname.equals(GROUP_NO_PROPERTY_NAME)
                        || propname.equals(ORIGINAL_ENTITY_ID_PROPERTY_NAME))
                    ix = Field.Index.NOT_ANALYZED; // so findRecordById will work
                else {
                    ix = Field.Index.ANALYZED;
                }
                // FIXME: it turns out that with the StandardAnalyzer you can't have a
                // multi-token value that's not analyzed if you want to find it again...
                // else
                //   ix = Field.Index.NOT_ANALYZED;

                for (String v : record.getValues(propname)) {
                    if (v.equals(""))
                        continue; // FIXME: not sure if this is necessary

                    doc.add(new Field(propname, v, Field.Store.YES, ix));
                }
            }
        }

        if (markAsDeleted) {
            doc.add(new Field("_deleted", "true", Field.Store.YES, Field.Index.NOT_ANALYZED));
        }

        try {
            iwriter.addDocument(doc);
        } catch (IOException e) {
            throw new DukeException(e);
        }
    }

    // We need this to be non-private
    void delete(Record record) {
        if (directory == null)
            init();

        // removes previous copy of this record from the index, if it's there
        Property idprop = config.getIdentityProperties().iterator().next();
        Query q = parseTokens(idprop.getName(), record.getValue(idprop.getName()));
        try {
            iwriter.deleteDocuments(q);
        } catch (IOException e) {
            throw new DukeException(e);
        }
    }


}