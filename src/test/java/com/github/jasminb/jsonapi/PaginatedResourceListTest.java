/*
 * Copyright 2017 Johns Hopkins University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jasminb.jsonapi;

import com.github.jasminb.jsonapi.PaginationTestUtils.Links;
import com.github.jasminb.jsonapi.PaginationTestUtils.Meta;
import com.github.jasminb.jsonapi.PaginationTestUtils.TestResource;
import org.junit.Test;

import java.util.List;
import java.util.Spliterator;
import java.util.stream.Collectors;

import static com.github.jasminb.jsonapi.PaginationTestUtils.ofIds;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyByte;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Elliot Metsger (emetsger@jhu.edu)
 */
public class PaginatedResourceListTest {

    private RelationshipResolver resolver = mock(RelationshipResolver.class);

    private ResourceConverter converter = mock(ResourceConverter.class);

    private final ResourceList<TestResource> resources = mock(ResourceList.class);

    private final Class<TestResource> clazz = TestResource.class;

    PaginatedResourceList<TestResource> underTest = new PaginatedResourceList(resources, resolver, converter, clazz);

    @Test
    public void testTotalAndPerPage() throws Exception {
        final Integer total = 1;
        final Integer perPage = 10;
        when(resources.getMeta()).thenReturn(new Meta<>(total, perPage));

        assertEquals(1, underTest.total());
        assertEquals(10, underTest.perPage());
        verify(resources, atLeastOnce()).getMeta();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNoTotalWithNextPage() throws Exception {
        // Three pages in the collection, of one result per page.
        ResourceList page_1 = new ResourceList<>(ofIds("1"));
        ResourceList page_2 = new ResourceList<>(ofIds("2"));
        ResourceList page_3 = new ResourceList<>(ofIds("3"));

        // Mocks a response that does not have a known total collection size, but has one result per page
        page_1.setMeta(new Meta<>(-1, 1));

        // Set the 'next' of page_1 to page_2, and page_2 to page_3
        page_1.setLinks(new Links(null, null, "page 2", null));
        page_2.setLinks(new Links(null, null, "page 3", null));

        // Mock the resolver's response
        when(resolver.resolve("page 2")).thenReturn("page 2".getBytes());
        when(resolver.resolve("page 3")).thenReturn("page 3".getBytes());

        when(converter.readObjectCollection(eq("page 2".getBytes()), any())).thenReturn(page_2);
        when(converter.readObjectCollection(eq("page 3".getBytes()), any())).thenReturn(page_3);


        underTest = new PaginatedResourceList(page_1, resolver, converter, clazz);


        // The collection can be paginated still (e.g. stream() will still work) but the collection is of unknown size
        assertEquals(-1, underTest.total());
        assertEquals(1, underTest.perPage());
        assertEquals(3, underTest.stream().count());

        verify(resolver, times(2)).resolve(any());
        verify(converter, times(2)).readObjectCollection(new byte[anyByte()], any());
    }

    @Test
    public void testNoTotalWithNoNextPage() throws Exception {
        final int size = 30;
        when(resources.getMeta()).thenReturn(null);
        when(resources.getNext()).thenReturn(null);
        when(resources.size()).thenReturn(size);

        // The collection cannot be paginated, there is no next page of results, so the size is equal to the size of the
        // underlying resource
        assertEquals(size, underTest.total());
        assertEquals(-1, underTest.perPage());

        verify(resources, atLeastOnce()).getMeta();
        verify(resources, atLeastOnce()).getNext();
        verify(resources, atLeastOnce()).size();
    }

    @Test
    public void testSpliteratorKnownSize() throws Exception {
        when(resources.getMeta()).thenReturn(new Meta<>(1, 10));

        final Spliterator result = underTest.spliterator();

        assertEquals(Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.SUBSIZED | Spliterator.SIZED,
                result.characteristics());
        assertEquals(1, result.getExactSizeIfKnown());
        verify(resources, atLeastOnce()).getMeta();
    }

    @Test
    public void testSpliteratorUnknownSize() throws Exception {
        when(resources.getMeta()).thenReturn(null);
        when(resources.getNext()).thenReturn("");

        final Spliterator result = underTest.spliterator();

        assertEquals(Spliterator.ORDERED | Spliterator.NONNULL, result.characteristics());
        verify(resources, atLeastOnce()).getMeta();
    }

    @Test
    public void testStreamSpliteratorKnownSize() throws Exception {
        final List testResources = ofIds("1", "2");
        when(resources.getMeta()).thenReturn(new Meta<>(testResources.size(), 10));
        when(resources.iterator()).thenReturn(testResources.iterator());

        // verify the initial state of the spliterator is sized
        assertEquals(Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.SUBSIZED | Spliterator.SIZED,
                underTest.spliterator().characteristics());

        final List results = underTest.stream().collect(Collectors.toList());

        assertEquals(testResources.size(), results.size());
        assertTrue(results.containsAll(testResources));
        verify(resources, atLeastOnce()).getMeta();
        verify(resources, times(2)).iterator();
    }

    @Test
    public void testStreamSpliteratorUnknownSize() throws Exception {
        final List testResources = ofIds("1", "2");
        when(resources.getMeta()).thenReturn(null);
        when(resources.getNext()).thenReturn("");
        when(resources.iterator()).thenReturn(testResources.iterator());

        // verify the initial state of the spliterator is unsized
        assertEquals(Spliterator.ORDERED | Spliterator.NONNULL, underTest.spliterator().characteristics());

        final List results = underTest.stream().collect(Collectors.toList());

        assertEquals(testResources.size(), results.size());
        assertTrue(results.containsAll(testResources));
        // once for verifying the initial state of the spliterator in the test, once for the execution in
        // PaginatedIterator
        verify(resources, times(2)).iterator();
        verify(resources, atLeastOnce()).getMeta();
    }

    @Test
    public void testIsEmpty() throws Exception {
        when(resources.isEmpty()).thenReturn(true);
        assertTrue(underTest.isEmpty());
        verify(resources).isEmpty();

        reset(resources);

        when(resources.isEmpty()).thenReturn(false);
        assertFalse(underTest.isEmpty());
        verify(resources).isEmpty();
    }

    @Test
    public void testToArray() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);

        final Object[] arrayResources = underTest.toArray();

        assertEquals(arrayResources.length, testResources.size());
        assertEquals(arrayResources[0], testResources.get(0));
        assertEquals(arrayResources[1], testResources.get(1));
        verifyForStream();
    }

    @Test
    public void testToTypedArray() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);

        final TestResource[] arrayResources = underTest.toArray(new TestResource[]{});

        assertEquals(arrayResources.length, testResources.size());
        assertEquals(arrayResources[0], testResources.get(0));
        assertEquals(arrayResources[1], testResources.get(1));
        verifyForStream();
    }

    @Test
    public void testToOversizedTypedArray() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);

        final TestResource[] arrayResources = underTest.toArray(new TestResource[testResources.size() + 1]);

        assertEquals(arrayResources.length, testResources.size() + 1);
        assertEquals(arrayResources[0], testResources.get(0));
        assertEquals(arrayResources[1], testResources.get(1));
        assertEquals(null, arrayResources[2]);
        verifyForStream();
    }

    @Test
    public void testToUndersizedTypedArray() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);

        final TestResource[] arrayResources = underTest.toArray(new TestResource[testResources.size() - 1]);

        assertEquals(arrayResources.length, testResources.size());
        assertEquals(arrayResources[0], testResources.get(0));
        assertEquals(arrayResources[1], testResources.get(1));
        verifyForStream();
    }

    @Test
    public void testParallelStreamSupport() throws Exception {
        final List testResources = ofIds("1", "2");

        prepareForStream(testResources);
        assertFalse(underTest.stream().isParallel());
        verifyForStream();

        reset(resources);

        prepareForStream(testResources);
        // parallel streams are not supported
        assertFalse(underTest.parallelStream().isParallel());
        verifyForStream();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetNegativeIndex() throws Exception {
        underTest.get(-1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetIndexExceedsSize() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);
        underTest.get(testResources.size() + 1);
    }

    @Test(expected = IndexOutOfBoundsException.class)
    public void testGetIndexExceedsSizeUnknownStreamLength() throws Exception {
        final List testResources = ofIds("1", "2");
        when(resources.getMeta()).thenReturn(null);
        when(resources.getNext()).thenReturn("");
        when(resources.iterator()).thenReturn(testResources.iterator());

        underTest.get(testResources.size() + 1);
    }

    @Test
    public void testGetIndex() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);

        assertEquals(testResources.get(1), underTest.get(1));

        verifyForStream();
    }

    @Test
    public void testContains() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);

        assertTrue(underTest.contains(testResources.get(0)));

        verifyForStream();
        reset(resources);
        prepareForStream(testResources);

        assertFalse(underTest.contains(new TestResource("3")));

        verifyForStream();
    }

    @Test
    public void testIndexOf() throws Exception {
        final List testResources = ofIds("1", "2");
        prepareForStream(testResources);

        assertEquals(0, underTest.indexOf(testResources.get(0)));

        verifyForStream();
        reset(resources);
        prepareForStream(testResources);

        assertEquals(-1, underTest.indexOf(new TestResource("3")));

        verifyForStream();
    }

    @Test
    public void testLastIndexOf() throws Exception {
        final List testResources = ofIds("1", "2", "2");
        prepareForStream(testResources);

        assertEquals(2, underTest.lastIndexOf(testResources.get(2)));

        verifyForStream();
        reset(resources);
        prepareForStream(testResources);

        assertEquals(-1, underTest.indexOf(new TestResource("3")));

        verifyForStream();
    }

    @Test
    public void testSubList() throws Exception {
        final List testResources = ofIds("1", "2", "3");
        prepareForStream(testResources);

        assertEquals(testResources.subList(0, 1), underTest.subList(0, 1));

        verifyForStream();
        reset(resources);
        prepareForStream(testResources);

        assertEquals(testResources.subList(1, 2), underTest.subList(1, 2));

        verifyForStream();
        reset(resources);
        prepareForStream(testResources);

        assertEquals(testResources.subList(0, 2), underTest.subList(0, 2));

        verifyForStream();
        reset(resources);
        prepareForStream(testResources);

        assertEquals(testResources.subList(2, 2), underTest.subList(2, 2));
    }

    /**
     * Prepares the mocks such that PaginatedListAdapter.stream will return a stream over the supplied list.
     *
     * @param toStream
     */
    @SuppressWarnings("unchecked")
    void prepareForStream(final List toStream) {
        when(resources.getMeta()).thenReturn(new Meta<>(toStream.size(), toStream.size()));
        when(resources.iterator()).thenReturn(toStream.iterator());
    }

    /**
     * Verify mocks that should have been acted on in order to return a stream.
     */
    void verifyForStream() {
        verify(resources, atLeastOnce()).getMeta();
        verify(resources).iterator();
    }

}