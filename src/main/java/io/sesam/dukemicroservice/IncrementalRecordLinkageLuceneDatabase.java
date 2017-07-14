package io.sesam.dukemicroservice;

import java.util.Collection;

import no.priv.garshol.duke.Record;

public class IncrementalRecordLinkageLuceneDatabase extends IncrementalLuceneDatabase {

    /**
     * We implement this in order to ignore all records from the same group
     */
    public Collection<Record> findCandidateMatches(Record record) {
        return findCandidateMatches(record, true);
    }
}