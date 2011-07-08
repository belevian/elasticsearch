/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.integration.nested;

import org.elasticsearch.action.admin.indices.status.IndicesStatusResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.search.facet.FacetBuilders;
import org.elasticsearch.search.facet.termsstats.TermsStatsFacet;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Arrays;

import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

@Test
public class SimpleNestedTests extends AbstractNodesTests {

    private Client client;

    @BeforeClass public void createNodes() throws Exception {
        startNode("node1");
        startNode("node2");
        client = client("node1");
    }

    @AfterClass public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    @Test public void simpleNested() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test")
                .addMapping("type1", jsonBuilder().startObject().startObject("type1").startObject("properties")
                        .startObject("nested1")
                        .field("type", "nested")
                        .endObject()
                        .endObject().endObject().endObject())
                .execute().actionGet();

        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();


        // check on no data, see it works
        SearchResponse searchResponse = client.prepareSearch("test").setQuery(termQuery("_all", "n_value1_1")).execute().actionGet();
        assertThat(searchResponse.hits().totalHits(), equalTo(0l));
        searchResponse = client.prepareSearch("test").setQuery(termQuery("nested1.n_field1", "n_value1_1")).execute().actionGet();
        assertThat(searchResponse.hits().totalHits(), equalTo(0l));

        client.prepareIndex("test", "type1", "1").setSource(jsonBuilder().startObject()
                .field("field1", "value1")
                .startArray("nested1")
                .startObject()
                .field("n_field1", "n_value1_1")
                .field("n_field2", "n_value2_1")
                .endObject()
                .startObject()
                .field("n_field1", "n_value1_2")
                .field("n_field2", "n_value2_2")
                .endObject()
                .endArray()
                .endObject()).execute().actionGet();

        // flush, so we fetch it from the index (as see that we filter nested docs)
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();
        GetResponse getResponse = client.prepareGet("test", "type1", "1").execute().actionGet();
        assertThat(getResponse.exists(), equalTo(true));

        // check the numDocs
        IndicesStatusResponse statusResponse = client.admin().indices().prepareStatus().execute().actionGet();
        assertThat(statusResponse.index("test").docs().numDocs(), equalTo(3));

        // check that _all is working on nested docs
        searchResponse = client.prepareSearch("test").setQuery(termQuery("_all", "n_value1_1")).execute().actionGet();
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        searchResponse = client.prepareSearch("test").setQuery(termQuery("nested1.n_field1", "n_value1_1")).execute().actionGet();
        assertThat(searchResponse.hits().totalHits(), equalTo(0l));

        // search for something that matches the nested doc, and see that we don't find the nested doc
        searchResponse = client.prepareSearch("test").setQuery(matchAllQuery()).execute().actionGet();
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        searchResponse = client.prepareSearch("test").setQuery(termQuery("nested1.n_field1", "n_value1_1")).execute().actionGet();
        assertThat(searchResponse.hits().totalHits(), equalTo(0l));

        // now, do a nested query
        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1", termQuery("nested1.n_field1", "n_value1_1"))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        // add another doc, one that would match if it was not nested...

        client.prepareIndex("test", "type1", "2").setSource(jsonBuilder().startObject()
                .field("field1", "value1")
                .startArray("nested1")
                .startObject()
                .field("n_field1", "n_value1_1")
                .field("n_field2", "n_value2_2")
                .endObject()
                .startObject()
                .field("n_field1", "n_value1_2")
                .field("n_field2", "n_value2_1")
                .endObject()
                .endArray()
                .endObject()).execute().actionGet();

        // flush, so we fetch it from the index (as see that we filter nested docs)
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        statusResponse = client.admin().indices().prepareStatus().execute().actionGet();
        assertThat(statusResponse.index("test").docs().numDocs(), equalTo(6));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                boolQuery().must(termQuery("nested1.n_field1", "n_value1_1")).must(termQuery("nested1.n_field2", "n_value2_1")))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        // filter
        searchResponse = client.prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), nestedFilter("nested1",
                boolQuery().must(termQuery("nested1.n_field1", "n_value1_1")).must(termQuery("nested1.n_field2", "n_value2_1"))))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        // check with type prefix
        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("type1.nested1",
                boolQuery().must(termQuery("nested1.n_field1", "n_value1_1")).must(termQuery("nested1.n_field2", "n_value2_1")))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        // check delete, so all is gone...
        DeleteResponse deleteResponse = client.prepareDelete("test", "type1", "2").execute().actionGet();
        assertThat(deleteResponse.notFound(), equalTo(false));

        // flush, so we fetch it from the index (as see that we filter nested docs)
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        statusResponse = client.admin().indices().prepareStatus().execute().actionGet();
        assertThat(statusResponse.index("test").docs().numDocs(), equalTo(3));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1", termQuery("nested1.n_field1", "n_value1_1"))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
    }

    @Test public void multiNested() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test")
                .addMapping("type1", jsonBuilder().startObject().startObject("type1").startObject("properties")
                        .startObject("nested1")
                        .field("type", "nested").startObject("properties")
                        .startObject("nested2").field("type", "nested").endObject()
                        .endObject().endObject()
                        .endObject().endObject().endObject())
                .execute().actionGet();

        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1", "1").setSource(jsonBuilder()
                .startObject()
                .field("field", "value")
                .startArray("nested1")
                .startObject().field("field1", "1").startArray("nested2").startObject().field("field2", "2").endObject().startObject().field("field2", "3").endObject().endArray().endObject()
                .startObject().field("field1", "4").startArray("nested2").startObject().field("field2", "5").endObject().startObject().field("field2", "6").endObject().endArray().endObject()
                .endArray()
                .endObject()).execute().actionGet();

        // flush, so we fetch it from the index (as see that we filter nested docs)
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();
        GetResponse getResponse = client.prepareGet("test", "type1", "1").execute().actionGet();
        assertThat(getResponse.exists(), equalTo(true));

        // check the numDocs
        IndicesStatusResponse statusResponse = client.admin().indices().prepareStatus().execute().actionGet();
        assertThat(statusResponse.index("test").docs().numDocs(), equalTo(7));

        // do some multi nested queries
        SearchResponse searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                termQuery("nested1.field1", "1"))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1.nested2",
                termQuery("nested1.nested2.field2", "2"))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                boolQuery().must(termQuery("nested1.field1", "1")).must(nestedQuery("nested1.nested2", termQuery("nested1.nested2.field2", "2"))))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                boolQuery().must(termQuery("nested1.field1", "1")).must(nestedQuery("nested1.nested2", termQuery("nested1.nested2.field2", "3"))))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                boolQuery().must(termQuery("nested1.field1", "1")).must(nestedQuery("nested1.nested2", termQuery("nested1.nested2.field2", "4"))))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(0l));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                boolQuery().must(termQuery("nested1.field1", "1")).must(nestedQuery("nested1.nested2", termQuery("nested1.nested2.field2", "5"))))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(0l));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                boolQuery().must(termQuery("nested1.field1", "4")).must(nestedQuery("nested1.nested2", termQuery("nested1.nested2.field2", "5"))))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        searchResponse = client.prepareSearch("test").setQuery(nestedQuery("nested1",
                boolQuery().must(termQuery("nested1.field1", "4")).must(nestedQuery("nested1.nested2", termQuery("nested1.nested2.field2", "2"))))).execute().actionGet();
        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(0l));
    }

    @Test public void testFacetsSingleShard() throws Exception {
        testFacets(1);
    }

    @Test public void testFacetsMultiShards() throws Exception {
        testFacets(3);
    }

    private void testFacets(int numberOfShards) throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test")
                .setSettings(ImmutableSettings.settingsBuilder().put("number_of_shards", numberOfShards))
                .addMapping("type1", jsonBuilder().startObject().startObject("type1").startObject("properties")
                        .startObject("nested1")
                        .field("type", "nested").startObject("properties")
                        .startObject("nested2").field("type", "nested").endObject()
                        .endObject().endObject()
                        .endObject().endObject().endObject())
                .execute().actionGet();

        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1", "1").setSource(jsonBuilder()
                .startObject()
                .field("field", "value")
                .startArray("nested1")
                .startObject().field("field1_1", "1").startArray("nested2").startObject().field("field2_1", "blue").field("field2_2", 5).endObject().startObject().field("field2_1", "yellow").field("field2_2", 3).endObject().endArray().endObject()
                .startObject().field("field1_1", "4").startArray("nested2").startObject().field("field2_1", "green").field("field2_2", 6).endObject().startObject().field("field2_1", "blue").field("field2_2", 1).endObject().endArray().endObject()
                .endArray()
                .endObject()).execute().actionGet();

        client.prepareIndex("test", "type1", "2").setSource(jsonBuilder()
                .startObject()
                .field("field", "value")
                .startArray("nested1")
                .startObject().field("field1_1", "2").startArray("nested2").startObject().field("field2_1", "yellow").field("field2_2", 10).endObject().startObject().field("field2_1", "green").field("field2_2", 8).endObject().endArray().endObject()
                .startObject().field("field1_1", "1").startArray("nested2").startObject().field("field2_1", "blue").field("field2_2", 2).endObject().startObject().field("field2_1", "red").field("field2_2", 12).endObject().endArray().endObject()
                .endArray()
                .endObject()).execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch("test").setQuery(matchAllQuery())
                .addFacet(FacetBuilders.termsStatsFacet("facet1").keyField("nested1.nested2.field2_1").valueField("nested1.nested2.field2_2").nested("nested1.nested2"))
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));

        TermsStatsFacet termsStatsFacet = searchResponse.facets().facet("facet1");
        assertThat(termsStatsFacet.entries().size(), equalTo(4));
        assertThat(termsStatsFacet.entries().get(0).term(), equalTo("blue"));
        assertThat(termsStatsFacet.entries().get(0).count(), equalTo(3l));
        assertThat(termsStatsFacet.entries().get(0).total(), equalTo(8d));
        assertThat(termsStatsFacet.entries().get(1).term(), equalTo("yellow"));
        assertThat(termsStatsFacet.entries().get(1).count(), equalTo(2l));
        assertThat(termsStatsFacet.entries().get(1).total(), equalTo(13d));
        assertThat(termsStatsFacet.entries().get(2).term(), equalTo("green"));
        assertThat(termsStatsFacet.entries().get(2).count(), equalTo(2l));
        assertThat(termsStatsFacet.entries().get(2).total(), equalTo(14d));
        assertThat(termsStatsFacet.entries().get(3).term(), equalTo("red"));
        assertThat(termsStatsFacet.entries().get(3).count(), equalTo(1l));
        assertThat(termsStatsFacet.entries().get(3).total(), equalTo(12d));

        // test scope ones
        searchResponse = client.prepareSearch("test")
                .setQuery(nestedQuery("nested1.nested2", termQuery("nested1.nested2.field2_1", "blue")).scope("my"))
                .addFacet(FacetBuilders.termsStatsFacet("facet1").keyField("nested1.nested2.field2_1").valueField("nested1.nested2.field2_2").scope("my"))
                .execute().actionGet();

        assertThat(Arrays.toString(searchResponse.shardFailures()), searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));

        termsStatsFacet = searchResponse.facets().facet("facet1");
        assertThat(termsStatsFacet.entries().size(), equalTo(1));
        assertThat(termsStatsFacet.entries().get(0).term(), equalTo("blue"));
        assertThat(termsStatsFacet.entries().get(0).count(), equalTo(3l));
        assertThat(termsStatsFacet.entries().get(0).total(), equalTo(8d));
    }
}