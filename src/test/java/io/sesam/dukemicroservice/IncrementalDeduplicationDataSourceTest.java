package io.sesam.dukemicroservice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.RecordIterator;
import no.priv.garshol.duke.datasources.Column;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

public class IncrementalDeduplicationDataSourceTest {
    @Test
    public void testSingleValuedArray() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        array.add("foo");
        object.add("array", array);
        object.add("_id", new JsonPrimitive("A"));

        IncrementalDeduplicationDataSource x = new IncrementalDeduplicationDataSource();
        x.addColumn(new Column("array", "array", "", null));
        JsonArray entities = new JsonArray();
        entities.add(object);
        x.setDatasetEntitiesBatch(entities);
        RecordIterator it = x.getRecords();
        Assert.assertTrue(it.hasNext());
        Record record = it.next();
        Assert.assertEquals(new HashSet<>(Arrays.asList("dukeDatasetId", "array", "dukeOriginalEntityId", "ID")), record.getProperties());
        Assert.assertEquals("null__A", record.getValue("ID"));
        Assert.assertEquals("foo", record.getValue("array"));
        Assert.assertEquals(Collections.singletonList("foo"), record.getValues("array"));
    }

    @Test
    public void testMultiValuedArray() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        array.add("foo");
        array.add("bar");
        object.add("array", array);
        object.add("_id", new JsonPrimitive("A"));

        IncrementalDeduplicationDataSource x = new IncrementalDeduplicationDataSource();
        x.addColumn(new Column("array", "array", "", null));
        JsonArray entities = new JsonArray();
        entities.add(object);
        x.setDatasetEntitiesBatch(entities);
        RecordIterator it = x.getRecords();
        Assert.assertTrue(it.hasNext());
        Record record = it.next();
        Assert.assertEquals(Arrays.asList("foo", "bar"), record.getValues("array"));
    }
}
