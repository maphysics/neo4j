/*
 * Copyright (c) 2002-2015 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.neo4j.io.pagecache.impl.ByteBufferPage;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public abstract class PageSwapperTest
{
    public static final PageEvictionCallback NO_CALLBACK = new PageEvictionCallback()
    {
        @Override
        public void onEvict( long pageId, Page page )
        {
        }
    };
    public static final long X = 0xcafebabedeadbeefl;
    public static final long Y = X ^ (X << 1);
    public static final int Z = 0xfefefefe;

    protected static final int cachePageSize = 32;

    protected abstract PageSwapperFactory swapperFactory() throws Exception;

    protected abstract void ensureFileExists( File file ) throws IOException;

    protected int cachePageSize()
    {
        return cachePageSize;
    }

    protected ByteBufferPage createPage( int cachePageSize )
    {
        return new ByteBufferPage( ByteBuffer.allocateDirect( cachePageSize ) );
    }

    protected ByteBufferPage createPage() {
        return createPage( cachePageSize() );
    }

    protected void clear( ByteBufferPage page )
    {
        byte b = (byte) 0;
        for ( int i = 0; i < cachePageSize(); i++ )
        {
            page.putByte( b, i );
        }
    }

    @Before
    @After
    public void clearStrayInterrupts()
    {
        Thread.interrupted();
    }

    @Test
    public void swappingOutMustNotSwallowInterrupts() throws Exception
    {
        File file = new File( "a" );
        ensureFileExists( file );

        ByteBufferPage page = createPage();
        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );

        Thread.currentThread().interrupt();

        swapper.write( 0, page );
        assertTrue( Thread.currentThread().isInterrupted() );
    }

    @Test
    public void forcingMustNotSwallowInterrupts() throws Exception
    {
        File file = new File( "a" );
        ensureFileExists( file );

        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );

        Thread.currentThread().interrupt();
        swapper.force();
        assertTrue( Thread.currentThread().isInterrupted() );
    }

    @Test
    public void mustReopenChannelWhenReadFailsWithAsynchronousCloseException() throws Exception
    {
        File file = new File( "a" );
        ensureFileExists( file );
        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );

        ByteBufferPage page = createPage();
        page.putLong( X, 0 );
        page.putLong( Y, 8 );
        page.putInt( Z, 16 );
        swapper.write( 0, page );

        Thread.currentThread().interrupt();

        swapper.read( 0, page );

        // Clear the interrupted flag and assert that it was still raised
        assertTrue( Thread.interrupted() );

        assertThat( page.getLong( 0 ), is( X ) );
        assertThat( page.getLong( 8 ), is( Y ) );
        assertThat( page.getInt( 16 ), is( Z ) );

        // This must not throw because we should still have a usable channel
        swapper.force();
    }

    @Test
    public void mustReopenChannelWhenWriteFailsWithAsynchronousCloseException() throws Exception
    {
        ByteBufferPage page = createPage();
        page.putLong( X, 0 );
        page.putLong( Y, 8 );
        page.putInt( Z, 16 );
        File file = new File( "a" );
        ensureFileExists( file );

        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );

        Thread.currentThread().interrupt();

        swapper.write( 0, page );

        // Clear the interrupted flag and assert that it was still raised
        assertTrue( Thread.interrupted() );

        // This must not throw because we should still have a usable channel
        swapper.force();

        clear( page );
        swapper.read( 0, page );
        assertThat( page.getLong( 0 ), is( X ) );
        assertThat( page.getLong( 8 ), is( Y ) );
        assertThat( page.getInt( 16 ), is( Z ) );
    }

    @Test
    public void mustReopenChannelWhenForceFailsWithAsynchronousCloseException() throws Exception
    {
        File file = new File( "a" );
        ensureFileExists( file );

        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );

        for ( int i = 0; i < 10; i++ )
        {
            Thread.currentThread().interrupt();

            // This must not throw
            swapper.force();

            // Clear the interrupted flag and assert that it was still raised
            assertTrue( Thread.interrupted() );
        }
    }

    @Test
    public void readMustNotReopenExplicitlyClosedChannel() throws Exception
    {
        File file = new File( "a" );
        ensureFileExists( file );

        ByteBufferPage page = createPage();
        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );
        swapper.write( 0, page );
        swapper.close();

        try
        {
            swapper.read( 0, page );
            fail( "Should have thrown because the channel should be closed" );
        }
        catch ( ClosedChannelException ignore )
        {
            // This is fine.
        }
    }

    @Test
    public void writeMustNotReopenExplicitlyClosedChannel() throws Exception
    {
        File file = new File( "a" );
        ensureFileExists( file );

        ByteBufferPage page = createPage();
        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );
        swapper.close();

        try
        {
            swapper.write( 0, page );
            fail( "Should have thrown because the channel should be closed" );
        }
        catch ( ClosedChannelException ignore )
        {
            // This is fine.
        }
    }

    @Test
    public void forceMustNotReopenExplicitlyClosedChannel() throws Exception
    {
        File file = new File( "a" );
        ensureFileExists( file );

        PageSwapperFactory swapperFactory = swapperFactory();
        PageSwapper swapper = swapperFactory.createPageSwapper( file, cachePageSize(), NO_CALLBACK );
        swapper.close();

        try
        {
            swapper.force();
            fail( "Should have thrown because the channel should be closed" );
        }
        catch ( ClosedChannelException ignore )
        {
            // This is fine.
        }
    }

    @Test
    public void mustNotOverwriteDataInOtherFiles() throws Exception {
        File fileA = new File( "a" );
        File fileB = new File( "b" );
        ensureFileExists( fileA );
        ensureFileExists( fileB );
        PageSwapperFactory factory = swapperFactory();
        PageSwapper swapperA =
                factory.createPageSwapper( fileA, cachePageSize(), NO_CALLBACK);
        PageSwapper swapperB =
                factory.createPageSwapper( fileB, cachePageSize(), NO_CALLBACK );

        ByteBufferPage page = createPage();
        page.putLong( X, 0 );
        swapperA.write( 0, page );
        page.putLong( Y, 8 );
        swapperB.write( 0, page );

        clear( page );
        swapperA.read( 0, page );

        assertThat( page.getLong( 0 ), is( X ) );
        assertThat( page.getLong( 8 ), is( 0L ) );
    }

    @Test
    public void mustRunEvictionCallbackOnEviction() throws Exception
    {
        final AtomicLong callbackFilePageId = new AtomicLong();
        final AtomicReference<Page> callbackPage = new AtomicReference<>();
        PageEvictionCallback callback = new PageEvictionCallback()
        {
            @Override
            public void onEvict( long filePageId, Page page )
            {
                callbackFilePageId.set( filePageId );
                callbackPage.set( page );
            }
        };
        File file = new File( "file" );
        ensureFileExists( file );
        PageSwapperFactory factory = swapperFactory();
        PageSwapper swapper = factory.createPageSwapper( file, cachePageSize(), callback );
        Page page = createPage();
        swapper.evicted( 42, page );
        assertThat( callbackFilePageId.get(), is( 42L ) );
        assertThat( callbackPage.get(), sameInstance( page ) );
    }

    @Test
    public void mustNotIssueEvictionCallbacksAfterSwapperHasBeenClosed() throws Exception
    {
        final AtomicBoolean gotCallback = new AtomicBoolean();
        PageEvictionCallback callback = new PageEvictionCallback()
        {
            @Override
            public void onEvict( long filePageId, Page page )
            {
                gotCallback.set( true );
            }
        };
        File file = new File( "file" );
        ensureFileExists( file );
        PageSwapperFactory factory = swapperFactory();
        PageSwapper swapper = factory.createPageSwapper( file, cachePageSize(), callback );
        Page page = createPage();
        swapper.close();
        swapper.evicted( 42, page );
        assertFalse( gotCallback.get() );
    }
}
