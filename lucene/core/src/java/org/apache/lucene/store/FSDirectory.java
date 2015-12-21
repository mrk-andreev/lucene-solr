package org.apache.lucene.store;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.FileNotFoundException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException; // javadoc @link
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.util.Constants;
import org.apache.lucene.util.IOUtils;

/**
 * Base class for Directory implementations that store index
 * files in the file system.  
 * <a name="subclasses"></a>
 * There are currently three core
 * subclasses:
 *
 * <ul>
 *
 *  <li>{@link SimpleFSDirectory} is a straightforward
 *       implementation using Files.newByteChannel.
 *       However, it has poor concurrent performance
 *       (multiple threads will bottleneck) as it
 *       synchronizes when multiple threads read from the
 *       same file.
 *
 *  <li>{@link NIOFSDirectory} uses java.nio's
 *       FileChannel's positional io when reading to avoid
 *       synchronization when reading from the same file.
 *       Unfortunately, due to a Windows-only <a
 *       href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6265734">Sun
 *       JRE bug</a> this is a poor choice for Windows, but
 *       on all other platforms this is the preferred
 *       choice. Applications using {@link Thread#interrupt()} or
 *       {@link Future#cancel(boolean)} should use
 *       {@code RAFDirectory} instead. See {@link NIOFSDirectory} java doc
 *       for details.
 *        
 *  <li>{@link MMapDirectory} uses memory-mapped IO when
 *       reading. This is a good choice if you have plenty
 *       of virtual memory relative to your index size, eg
 *       if you are running on a 64 bit JRE, or you are
 *       running on a 32 bit JRE but your index sizes are
 *       small enough to fit into the virtual memory space.
 *       Java has currently the limitation of not being able to
 *       unmap files from user code. The files are unmapped, when GC
 *       releases the byte buffers. Due to
 *       <a href="http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4724038">
 *       this bug</a> in Sun's JRE, MMapDirectory's {@link IndexInput#close}
 *       is unable to close the underlying OS file handle. Only when
 *       GC finally collects the underlying objects, which could be
 *       quite some time later, will the file handle be closed.
 *       This will consume additional transient disk usage: on Windows,
 *       attempts to delete or overwrite the files will result in an
 *       exception; on other platforms, which typically have a &quot;delete on
 *       last close&quot; semantics, while such operations will succeed, the bytes
 *       are still consuming space on disk.  For many applications this
 *       limitation is not a problem (e.g. if you have plenty of disk space,
 *       and you don't rely on overwriting files on Windows) but it's still
 *       an important limitation to be aware of. This class supplies a
 *       (possibly dangerous) workaround mentioned in the bug report,
 *       which may fail on non-Sun JVMs.
 * </ul>
 *
 * <p>Unfortunately, because of system peculiarities, there is
 * no single overall best implementation.  Therefore, we've
 * added the {@link #open} method, to allow Lucene to choose
 * the best FSDirectory implementation given your
 * environment, and the known limitations of each
 * implementation.  For users who have no reason to prefer a
 * specific implementation, it's best to simply use {@link
 * #open}.  For all others, you should instantiate the
 * desired implementation directly.
 *
 * <p><b>NOTE:</b> Accessing one of the above subclasses either directly or
 * indirectly from a thread while it's interrupted can close the
 * underlying channel immediately if at the same time the thread is
 * blocked on IO. The channel will remain closed and subsequent access
 * to the index will throw a {@link ClosedChannelException}.
 * Applications using {@link Thread#interrupt()} or
 * {@link Future#cancel(boolean)} should use the slower legacy
 * {@code RAFDirectory} from the {@code misc} Lucene module instead.
 *
 * <p>The locking implementation is by default {@link
 * NativeFSLockFactory}, but can be changed by
 * passing in a custom {@link LockFactory} instance.
 *
 * @see Directory
 */
public abstract class FSDirectory extends BaseDirectory {

  protected final Path directory; // The underlying filesystem directory

  /** Files we previously tried to delete, but hit exception (on Windows) last time we tried.
   *  These files are in "pending delete" state, where we refuse to openInput or createOutput
   *  them, nor include them in .listAll. */
  protected final Set<String> pendingDeletes = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());

  /** Create a new FSDirectory for the named location (ctor for subclasses).
   * The directory is created at the named location if it does not yet exist.
   * 
   * <p>{@code FSDirectory} resolves the given Path to a canonical /
   * real path to ensure it can correctly lock the index directory and no other process
   * can interfere with changing possible symlinks to the index directory inbetween.
   * If you want to use symlinks and change them dynamically, close all
   * {@code IndexWriters} and create a new {@code FSDirecory} instance.
   * @param path the path of the directory
   * @param lockFactory the lock factory to use, or null for the default
   * ({@link NativeFSLockFactory});
   * @throws IOException if there is a low-level I/O error
   */
  protected FSDirectory(Path path, LockFactory lockFactory) throws IOException {
    super(lockFactory);
    // If only read access is permitted, createDirectories fails even if the directory already exists.
    if (!Files.isDirectory(path)) {
      Files.createDirectories(path);  // create directory, if it doesn't exist
    }
    directory = path.toRealPath();
  }

  /** Creates an FSDirectory instance, trying to pick the
   *  best implementation given the current environment.
   *  The directory returned uses the {@link NativeFSLockFactory}.
   *  The directory is created at the named location if it does not yet exist.
   * 
   * <p>{@code FSDirectory} resolves the given Path when calling this method to a canonical /
   * real path to ensure it can correctly lock the index directory and no other process
   * can interfere with changing possible symlinks to the index directory inbetween.
   * If you want to use symlinks and change them dynamically, close all
   * {@code IndexWriters} and create a new {@code FSDirecory} instance.
   *
   *  <p>Currently this returns {@link MMapDirectory} for Linux, MacOSX, Solaris,
   *  and Windows 64-bit JREs, {@link NIOFSDirectory} for other
   *  non-Windows JREs, and {@link SimpleFSDirectory} for other
   *  JREs on Windows. It is highly recommended that you consult the
   *  implementation's documentation for your platform before
   *  using this method.
   *
   * <p><b>NOTE</b>: this method may suddenly change which
   * implementation is returned from release to release, in
   * the event that higher performance defaults become
   * possible; if the precise implementation is important to
   * your application, please instantiate it directly,
   * instead. For optimal performance you should consider using
   * {@link MMapDirectory} on 64 bit JVMs.
   *
   * <p>See <a href="#subclasses">above</a> */
  public static FSDirectory open(Path path) throws IOException {
    return open(path, FSLockFactory.getDefault());
  }

  /** Just like {@link #open(Path)}, but allows you to
   *  also specify a custom {@link LockFactory}. */
  public static FSDirectory open(Path path, LockFactory lockFactory) throws IOException {
    if (Constants.JRE_IS_64BIT && MMapDirectory.UNMAP_SUPPORTED) {
      return new MMapDirectory(path, lockFactory);
    } else if (Constants.WINDOWS) {
      return new SimpleFSDirectory(path, lockFactory);
    } else {
      return new NIOFSDirectory(path, lockFactory);
    }
  }

  /** Lists all files (including subdirectories) in the
   *  directory.
   *
   *  @throws IOException if there was an I/O error during listing */
  public static String[] listAll(Path dir) throws IOException {
    return listAll(dir, Collections.emptySet());
  }

  private static String[] listAll(Path dir, Set<String> skipNames) throws IOException {
    List<String> entries = new ArrayList<>();
    
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path path : stream) {
        String name = path.getFileName().toString();
        if (skipNames.contains(name) == false) {
          entries.add(name);
        }
      }
    }
    
    return entries.toArray(new String[entries.size()]);
  }

  @Override
  public String[] listAll() throws IOException {
    ensureOpen();
    return listAll(directory, pendingDeletes);
  }

  /** Returns the length in bytes of a file in the directory. */
  @Override
  public long fileLength(String name) throws IOException {
    ensureOpen();
    return Files.size(directory.resolve(name));
  }

  /** Removes an existing file in the directory. */
  @Override
  public void deleteFiles(Collection<String> names) throws IOException {
    ensureOpen();
    pendingDeletes.addAll(names);
    deletePendingFiles();
  }

  /** Creates an IndexOutput for the file with the given name. */
  @Override
  public IndexOutput createOutput(String name, IOContext context) throws IOException {
    ensureOpen();
    ensureCanWrite(name);
    return new FSIndexOutput(name);
  }

  @Override
  public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
    ensureOpen();
    while (true) {
      String name = prefix + tempFileRandom.nextInt(Integer.MAX_VALUE) + "." + suffix;
      try {
        return new FSIndexOutput(name, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException faee) {
        // Retry with next random name
      }
    }
  }

  // nocommit openInput must refuse if it's a pending delete file?

  protected void ensureCanWrite(String name) throws IOException {
    deletePendingFiles();
    if (pendingDeletes.contains(name)) {
      throw new IOException("file \"" + name + "\" is pending delete and cannot be overwritten");
    }
    Files.deleteIfExists(directory.resolve(name)); // delete existing, if any
  }

  protected void ensureCanRead(String name) throws IOException {
    deletePendingFiles();
    if (pendingDeletes.contains(name)) {
      throw new IOException("file \"" + name + "\" is pending delete and cannot be overwritten");
    }
    if (pendingDeletes.contains(name)) {
      throw new IOException("file \"" + name + "\" is pending delete and cannot be opened");
    }
  }

  @Override
  public void sync(Collection<String> names) throws IOException {
    ensureOpen();

    for (String name : names) {
      fsync(name);
    }
  }
  
  @Override
  public void renameFile(String source, String dest) throws IOException {
    ensureOpen();
    Files.move(directory.resolve(source), directory.resolve(dest), StandardCopyOption.ATOMIC_MOVE);
    // TODO: should we move directory fsync to a separate 'syncMetadata' method?
    // for example, to improve listCommits(), IndexFileDeleter could also call that after deleting segments_Ns
    IOUtils.fsync(directory, true);
  }

  /** Closes the store to future operations. */
  @Override
  public synchronized void close() throws IOException {
    isOpen = false;
    deletePendingFiles();
  }

  /** @return the underlying filesystem directory */
  public Path getDirectory() {
    ensureOpen();
    return directory;
  }

  /** For debug output. */
  @Override
  public String toString() {
    return this.getClass().getSimpleName() + "@" + directory + " lockFactory=" + lockFactory;
  }

  protected void fsync(String name) throws IOException {
    deletePendingFiles();
    IOUtils.fsync(directory.resolve(name), false);
  }

  /** Returns true if the file was successfully removed. */
  private boolean deleteFile(String name) throws IOException {  
    try {
      Files.delete(directory.resolve(name));
      pendingDeletes.remove(name);
      return true;
    } catch (NoSuchFileException | FileNotFoundException e) {
      // We were asked to delete a non-existent file:
      pendingDeletes.remove(name);
      throw e;
    } catch (IOException ioe) {
      // On windows, a file delete can fail because there's still an open
      // file handle against it.  We record this in pendingDeletes and
      // try again later.

      // TODO: this is hacky/lenient (we don't know which IOException this is), and
      // it should only happen on filesystems that can do this, so really we should
      // move this logic to WindowsDirectory or something

      // nocommit can/should we do if (Constants.WINDOWS) here, else throw the exc?
      // but what about a Linux box with a CIFS mount?
      System.out.println("FS.deleteFile failed (" + ioe + "): will retry later");
      pendingDeletes.add(name);
      return false;
    }
  }

  /** Tries to delete any pending deleted files, and returns true if
   *  there are still files that could not be deleted. */
  public boolean checkPendingDeletions() throws IOException {
    deletePendingFiles();
    return pendingDeletes.isEmpty() == false;
  }

  /** Try to delete any pending files that we had previously tried to delete but failed
   *  because we are on Windows and the files were still
   *  held open. */
  public void deletePendingFiles() throws IOException {
    // TODO: we could fix IndexInputs from FSDirectory subclasses to call this when they are closed?

    // Clone the set because it will change as we iterate:
    List<String> toDelete = new ArrayList<>(pendingDeletes);

    // First pass: delete any segments_N files.  We do these first to be certain stale commit points are removed
    // before we remove any files they reference.  If any delete of segments_N fails, we leave all other files
    // undeleted so index is never in a corrupt state:
    for (String fileName : toDelete) {
      if (fileName.startsWith(IndexFileNames.SEGMENTS)) {
        if (deleteFile(fileName) == false) {
          return;
        }
      }
    }

    // Only delete other files if we were able to remove the segments_N files; this way we never
    // leave a corrupt commit in the index even in the presense of virus checkers:
    for(String fileName : toDelete) {
      if (fileName.startsWith(IndexFileNames.SEGMENTS) == false) {
        // nocommit if we hit exc here ... should we continue deleting the rest, and do the addSuppressed thing...?
        deleteFile(fileName);
      }
    }
  }

  final class FSIndexOutput extends OutputStreamIndexOutput {
    /**
     * The maximum chunk size is 8192 bytes, because file channel mallocs
     * a native buffer outside of stack if the write buffer size is larger.
     */
    static final int CHUNK_SIZE = 8192;
    
    public FSIndexOutput(String name) throws IOException {
      this(name, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    FSIndexOutput(String name, OpenOption... options) throws IOException {
      super("FSIndexOutput(path=\"" + directory.resolve(name) + "\")", name, new FilterOutputStream(Files.newOutputStream(directory.resolve(name), options)) {
        // This implementation ensures, that we never write more than CHUNK_SIZE bytes:
        @Override
        public void write(byte[] b, int offset, int length) throws IOException {
          while (length > 0) {
            final int chunk = Math.min(length, CHUNK_SIZE);
            out.write(b, offset, chunk);
            length -= chunk;
            offset += chunk;
          }
        }
      }, CHUNK_SIZE);
    }
  }
}
