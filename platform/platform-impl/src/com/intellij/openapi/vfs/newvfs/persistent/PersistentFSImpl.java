// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent;

import com.intellij.concurrency.ConcurrentCollectionFactory;
import com.intellij.concurrency.JobSchedulerImpl;
import com.intellij.diagnostic.Activity;
import com.intellij.diagnostic.ActivityCategory;
import com.intellij.diagnostic.StartUpMeasurer;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.InternalFileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.util.PingProgress;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.*;
import com.intellij.openapi.util.text.Strings;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import com.intellij.openapi.vfs.encoding.Utf8BomOptionProvider;
import com.intellij.openapi.vfs.ex.temp.TempFileSystem;
import com.intellij.openapi.vfs.impl.local.LocalFileSystemImpl;
import com.intellij.openapi.vfs.newvfs.*;
import com.intellij.openapi.vfs.newvfs.events.*;
import com.intellij.openapi.vfs.newvfs.impl.*;
import com.intellij.openapi.vfs.newvfs.persistent.log.VfsLog;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import com.intellij.util.*;
import com.intellij.util.containers.*;
import com.intellij.util.io.ReplicatorInputStream;
import com.intellij.util.io.storage.HeavyProcessLatch;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Queue;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

public final class PersistentFSImpl extends PersistentFS implements Disposable {
  private static final Logger LOG = Logger.getInstance(PersistentFSImpl.class);

  private final Map<String, VirtualFileSystemEntry> myRoots;

  private final VirtualDirectoryCache myIdToDirCache = new VirtualDirectoryCache();
  private final ReadWriteLock myInputLock = new ReentrantReadWriteLock();

  private final AtomicBoolean myConnected = new AtomicBoolean(false);
  private final AtomicInteger myStructureModificationCount = new AtomicInteger();
  private BulkFileListener myPublisher;
  private volatile VfsData myVfsData;
  private final Application app;
  private VfsLog myVfsLog;

  public PersistentFSImpl(@NotNull Application app) {
    this.app = app;
    myVfsData = new VfsData(app);
    myRoots = SystemInfoRt.isFileSystemCaseSensitive
              ? new ConcurrentHashMap<>(10, 0.4f, JobSchedulerImpl.getCPUCoresCount())
              : ConcurrentCollectionFactory.createConcurrentMap(10, 0.4f, JobSchedulerImpl.getCPUCoresCount(),
                                                                HashingStrategy.caseInsensitive());

    //PersistentFSImpl is an application service, and generally disposed as such, via .dispose().
    // But to be on the safe side -- add a shutdown task also:
    ShutDownTracker.getInstance().registerShutdownTask(this::disconnect);
    LowMemoryWatcher.register(this::clearIdCache, this);

    AsyncEventSupport.startListening();

    app.getMessageBus().simpleConnect().subscribe(DynamicPluginListener.TOPIC, new DynamicPluginListener(){
      @Override
      public void pluginUnloaded(@NotNull IdeaPluginDescriptor pluginDescriptor, boolean isUpdate) {
        // `myIdToDirCache` could retain alien file systems
        clearIdCache();
        // remove alien file system references from myRoots
        for (Iterator<Map.Entry<String, VirtualFileSystemEntry>> iterator = myRoots.entrySet().iterator(); iterator.hasNext(); ) {
          Map.Entry<String, VirtualFileSystemEntry> entry = iterator.next();
          VirtualFileSystemEntry root = entry.getValue();
          if (VirtualFileManager.getInstance().getFileSystem(root.getFileSystem().getProtocol()) == null) {
            // the file system must have been unregistered
            iterator.remove();
          }
        }
      }
    });

    connect();
  }

  private void initVfsLog() {
    var readOnly = !VfsLog.LOG_VFS_OPERATIONS_ENABLED;
    myVfsLog = new VfsLog(Path.of(FSRecords.getCachesDir() + "/vfslog"), readOnly);
  }

  @ApiStatus.Internal
  public void connect() {
    myIdToDirCache.clear();
    myVfsData = new VfsData(app);
    LOG.assertTrue(!myConnected.get());
    initVfsLog();
    doConnect();
    PersistentFsConnectionListener.EP_NAME.getExtensionList().forEach(PersistentFsConnectionListener::connectionOpen);
  }

  @ApiStatus.Internal
  public void disconnect() {
    if (myConnected.compareAndSet(true, false)) {
      PersistentFsConnectionListener.EP_NAME.getExtensionList().forEach(PersistentFsConnectionListener::beforeConnectionClosed);
      // TODO make sure we don't have files in memory
      FileNameCache.drop();
      myRoots.clear();
      myIdToDirCache.clear();
      long ms = System.currentTimeMillis();
      LOG.info("VFS dispose started");
      FSRecords.dispose();
      LOG.info("VFS dispose completed in " + (System.currentTimeMillis() - ms) + "ms.");
      myVfsLog.dispose();
    }
  }

  private void doConnect() {
    if (myConnected.compareAndSet(false, true)) {
      Activity activity = StartUpMeasurer.startActivity("connect FSRecords", ActivityCategory.DEFAULT);
      FSRecords.connect(myVfsLog.getConnectionInterceptors());
      activity.end();
    }
  }

  @ApiStatus.Internal
  public boolean isConnected() {
    return myConnected.get();
  }

  private @NotNull BulkFileListener getPublisher() {
    BulkFileListener publisher = myPublisher;
    if (publisher == null) {
      // the field cannot be initialized in constructor, to ensure that lazy listeners won't be created too early
      publisher = app.getMessageBus().syncPublisher(VirtualFileManager.VFS_CHANGES);
      myPublisher = publisher;
    }
    return publisher;
  }

  @Override
  public void dispose() {
    disconnect();
  }

  @Override
  public boolean areChildrenLoaded(@NotNull VirtualFile dir) {
    return areChildrenLoaded(getFileId(dir));
  }

  @Override
  public long getCreationTimestamp() {
    return FSRecords.getCreationTimestamp();
  }

  public @NotNull VirtualFileSystemEntry getOrCacheDir(@NotNull VirtualDirectoryImpl newDir) {
    return myIdToDirCache.getOrCacheDir(newDir);
  }

  public VirtualFileSystemEntry getCachedDir(int id) {
    return myIdToDirCache.getCachedDir(id);
  }

  private static @NotNull NewVirtualFileSystem getFileSystem(@NotNull VirtualFile file) {
    return (NewVirtualFileSystem)file.getFileSystem();
  }

  @Override
  public boolean wereChildrenAccessed(@NotNull VirtualFile dir) {
    return FSRecords.wereChildrenAccessed(getFileId(dir));
  }

  @Override
  public String @NotNull [] list(@NotNull VirtualFile file) {
    List<? extends ChildInfo> children = listAll(file);
    return ContainerUtil.map2Array(children, String.class, id -> id.getName().toString());
  }

  @Override
  public String @NotNull [] listPersisted(@NotNull VirtualFile parent) {
    int[] childrenIds = FSRecords.listIds(getFileId(parent));
    String[] names = ArrayUtil.newStringArray(childrenIds.length);
    for (int i = 0; i < childrenIds.length; i++) {
      names[i] = FSRecords.getName(childrenIds[i]);
    }
    return names;
  }

  // return actual children
  private static @NotNull List<? extends ChildInfo> persistAllChildren(VirtualFile dir, int id) {
    NewVirtualFileSystem fs = getFileSystem(dir);

    try {
      String[] fsNames = VfsUtil.filterNames(fs instanceof LocalFileSystemImpl ? ((LocalFileSystemImpl)fs).listWithCaching(dir) : fs.list(dir));

      Map<String, ChildInfo> justCreated = new HashMap<>();
      ListResult saved = FSRecords.update(dir, id, current -> {
        List<? extends ChildInfo> currentChildren = current.children;
        if (fsNames.length == 0 && !currentChildren.isEmpty()) {
          return current;
        }
        // preserve current children which match fsNames (to have stable id)
        // (on case-insensitive systems, replace those from current with case-changes ones from fsNames preserving the id)
        // add those from fsNames which are absent from current
        boolean caseSensitive = dir.isCaseSensitive();
        Set<String> toAddNames = CollectionFactory.createFilePathSet(fsNames, caseSensitive);
        for (ChildInfo currentChild : currentChildren) {
          toAddNames.remove(currentChild.getName().toString());
        }

        List<ChildInfo> toAddChildren = new ArrayList<>(toAddNames.size());
        if (fs instanceof BatchingFileSystem) {
          Map<String, FileAttributes> map = ((BatchingFileSystem)fs).listWithAttributes(dir, toAddNames);
          for (Map.Entry<String, FileAttributes> entry : map.entrySet()) {
            String newName = entry.getKey();
            FileAttributes attrs = entry.getValue();
            String target = attrs.isSymLink() ? fs.resolveSymLink(new FakeVirtualFile(dir, newName)) : null;
            Pair<@NotNull FileAttributes, String> childData = getChildData(fs, dir, newName, attrs, target);
            ChildInfo newChild = justCreated.computeIfAbsent(newName, name -> makeChildRecord(dir, id, name, childData, fs, null));
            toAddChildren.add(newChild);
          }
        }
        else {
          for (String newName : toAddNames) {
            Pair<@NotNull FileAttributes, String> childData = getChildData(fs, dir, newName, null, null);
            if (childData != null) {
              ChildInfo newChild = justCreated.computeIfAbsent(newName, name -> makeChildRecord(dir, id, name, childData, fs, null));
              toAddChildren.add(newChild);
            }
          }
        }

        // some clients (e.g. RefreshWorker) expect subsequent list() calls to return equal arrays
        toAddChildren.sort(ChildInfo.BY_ID);
        return current.merge(toAddChildren, caseSensitive);
      });

      setChildrenCached(id);

      return saved.children;
    }
    finally {
      if (fs instanceof LocalFileSystemImpl) {
        ((LocalFileSystemImpl)fs).clearListCache();
      }
    }
  }

  private static void setChildrenCached(int id) {
    int flags = FSRecords.getFlags(id);
    FSRecords.setFlags(id, flags | Flags.CHILDREN_CACHED);
  }

  @Override
  @ApiStatus.Internal
  public @NotNull List<? extends ChildInfo> listAll(@NotNull VirtualFile file) {
    int id = getFileId(file);
    return areChildrenLoaded(id)
           ? FSRecords.list(id).children
           : persistAllChildren(file, id);
  }

  private static boolean areChildrenLoaded(int parentId) {
    return BitUtil.isSet(FSRecords.getFlags(parentId), Flags.CHILDREN_CACHED);
  }

  @Override
  public @Nullable AttributeInputStream readAttribute(@NotNull VirtualFile file, @NotNull FileAttribute att) {
    return FSRecords.readAttributeWithLock(getFileId(file), att);
  }

  @Override
  public @NotNull AttributeOutputStream writeAttribute(@NotNull VirtualFile file, @NotNull FileAttribute att) {
    return FSRecords.writeAttribute(getFileId(file), att);
  }

  private static @Nullable DataInputStream readContent(@NotNull VirtualFile file) {
    return FSRecords.readContent(getFileId(file));
  }

  private static @NotNull DataInputStream readContentById(int contentId) {
    return FSRecords.readContentById(contentId);
  }

  private static @NotNull DataOutputStream writeContent(@NotNull VirtualFile file, boolean readOnly) {
    return FSRecords.writeContent(getFileId(file), readOnly);
  }

  private static void writeContent(@NotNull VirtualFile file, @NotNull ByteArraySequence content, boolean readOnly) {
    FSRecords.writeContent(getFileId(file), content, readOnly);
  }

  @Override
  public int storeUnlinkedContent(byte @NotNull [] bytes) {
    return FSRecords.storeUnlinkedContent(bytes);
  }

  @Override
  public int getModificationCount(@NotNull VirtualFile file) {
    return FSRecords.getModCount(getFileId(file));
  }

  @Override
  @Deprecated
  public int getModificationCount() {
    return FSRecords.getLocalModCount();
  }

  @Override
  public int getStructureModificationCount() {
    return myStructureModificationCount.get();
  }

  public void incStructuralModificationCount() {
    myStructureModificationCount.incrementAndGet();
  }

  @TestOnly
  @Override
  public int getFilesystemModificationCount() {
    return FSRecords.getPersistentModCount();
  }

  // returns `nameId` > 0 if write successful, -1 if not
  private static int writeAttributesToRecord(int id, int parentId, @NotNull CharSequence name, boolean cs, @NotNull FileAttributes attributes, boolean overwriteMissed) {
    assert id > 0 : id;
    assert parentId >= 0 : parentId; // 0 means there's no parent
    if (!name.isEmpty()) {
      if (Comparing.equal(name, FSRecords.getNameSequence(id), cs)) return -1; // TODO: Handle root attributes change.
    }
    else {
      if (areChildrenLoaded(id)) return -1; // TODO: hack
    }

    return FSRecords.writeAttributesToRecord(id, parentId, attributes, name.toString(), overwriteMissed);
  }

  @Override
  public @Attributes int getFileAttributes(int id) {
    assert id > 0;
    return FSRecords.getFlags(id);
  }

  @Override
  public boolean isDirectory(@NotNull VirtualFile file) {
    return isDirectory(getFileAttributes(getFileId(file)));
  }

  @Override
  public boolean exists(@NotNull VirtualFile fileOrDirectory) {
    return fileOrDirectory.exists();
  }

  @Override
  public long getTimeStamp(@NotNull VirtualFile file) {
    return FSRecords.getTimestamp(getFileId(file));
  }

  @Override
  public void setTimeStamp(@NotNull VirtualFile file, long modStamp) throws IOException {
    int id = getFileId(file);
    FSRecords.setTimestamp(id, modStamp);
    getFileSystem(file).setTimeStamp(file, modStamp);
  }

  private static int getFileId(@NotNull VirtualFile file) {
    return ((VirtualFileWithId)file).getId();
  }

  @Override
  public boolean isSymLink(@NotNull VirtualFile file) {
    return isSymLink(getFileAttributes(getFileId(file)));
  }

  @Override
  public String resolveSymLink(@NotNull VirtualFile file) {
    return FSRecords.readSymlinkTarget(getFileId(file));
  }

  @Override
  public boolean isWritable(@NotNull VirtualFile file) {
    return !BitUtil.isSet(getFileAttributes(getFileId(file)), Flags.IS_READ_ONLY);
  }

  @Override
  public boolean isHidden(@NotNull VirtualFile file) {
    return BitUtil.isSet(getFileAttributes(getFileId(file)), Flags.IS_HIDDEN);
  }

  @Override
  public void setWritable(@NotNull VirtualFile file, boolean writableFlag) throws IOException {
    getFileSystem(file).setWritable(file, writableFlag);
    boolean oldWritable = isWritable(file);
    if (oldWritable != writableFlag) {
      processEvent(new VFilePropertyChangeEvent(this, file, VirtualFile.PROP_WRITABLE, oldWritable, writableFlag, false));
    }
  }

  @Override
  @ApiStatus.Internal
  public ChildInfo findChildInfo(@NotNull VirtualFile parent, @NotNull String childName, @NotNull NewVirtualFileSystem fs) {
    int parentId = getFileId(parent);
    Ref<ChildInfo> result = new Ref<>();

    Function<ListResult, ListResult> convertor = children -> {
      ChildInfo child = findExistingChildInfo(parent, childName, children.children);
      if (child != null) {
        result.set(child);
        return children;
      }
      Pair<@NotNull FileAttributes, String> childData = getChildData(fs, parent, childName, null, null); // todo: use BatchingFileSystem
      if (childData == null) {
        return children;
      }
      String canonicalName;
      if (parent.isCaseSensitive()) {
        canonicalName = childName;
      }
      else {
        canonicalName = fs.getCanonicallyCasedName(new FakeVirtualFile(parent, childName));
        if (Strings.isEmptyOrSpaces(canonicalName)) {
          return children;
        }

        if (!childName.equals(canonicalName)) {
          child = findExistingChildInfo(parent, canonicalName, children.children);
          result.set(child);
        }
      }
      if (child == null) {
        if (result.isNull()) {
          child = makeChildRecord(parent, parentId, canonicalName, childData, fs, null);
          result.set(child);
        }
        else {
          // might have stored on a previous attempt
          child = result.get();
        }
        return children.insert(child);
      }
      return children;
    };
    FSRecords.update(parent, parentId, convertor);
    return result.get();
  }

  private static ChildInfo findExistingChildInfo(@NotNull VirtualFile parent, @NotNull String childName, @NotNull List<? extends ChildInfo> children) {
    if (children.isEmpty()) {
      return null;
    }
    // fast path, check that some child has the same `nameId` as a given name - to avoid an overhead on retrieving names of non-cached children
    int nameId = FSRecords.getNameId(childName);
    for (ChildInfo info : children) {
      if (nameId == info.getNameId()) {
        return info;
      }
    }
    //FIXME RC: above check is 'strict' only if nameId is a unique identifier for a name. We're going to change that, hence
    //          the code below should be run not only for caseSensitive systems, but for all them, as 'slow path'
    // for case-sensitive systems, the above check is exhaustive in consistent state of VFS
    if (!parent.isCaseSensitive()) {
      for (ChildInfo info : children) {
        if (Comparing.equal(childName, FileNameCache.getVFileName(info.getNameId()), false)) {
          return info;
        }
      }
    }
    return null;
  }

  @Override
  public long getLength(@NotNull VirtualFile file) {
    long length = getLengthIfUpToDate(file);
    return length == -1 ? reloadLengthFromFS(file, getFileSystem(file)) : length;
  }

  @Override
  public long getLastRecordedLength(@NotNull VirtualFile file) {
    int id = getFileId(file);
    return FSRecords.getLength(id);
  }

  @Override
  public @NotNull VirtualFile copyFile(Object requestor, @NotNull VirtualFile file, @NotNull VirtualFile parent, @NotNull String name) throws IOException {
    getFileSystem(file).copyFile(requestor, file, parent, name);
    processEvent(new VFileCopyEvent(requestor, file, parent, name));

    VirtualFile child = parent.findChild(name);
    if (child == null) {
      throw new IOException("Cannot create child");
    }
    return child;
  }

  @Override
  public @NotNull VirtualFile createChildDirectory(Object requestor, @NotNull VirtualFile parent, @NotNull String dir) throws IOException {
    getFileSystem(parent).createChildDirectory(requestor, parent, dir);

    processEvent(new VFileCreateEvent(requestor, parent, dir, true, null, null, false, ChildInfo.EMPTY_ARRAY));
    VFileEvent caseSensitivityEvent = VirtualDirectoryImpl.generateCaseSensitivityChangedEventForUnknownCase(parent, dir);
    if (caseSensitivityEvent != null) {
      processEvent(caseSensitivityEvent);
    }

    VirtualFile child = parent.findChild(dir);
    if (child == null) {
      throw new IOException("Cannot create child directory '" + dir + "' at " + parent.getPath());
    }
    return child;
  }

  @Override
  public @NotNull VirtualFile createChildFile(Object requestor, @NotNull VirtualFile parent, @NotNull String name) throws IOException {
    getFileSystem(parent).createChildFile(requestor, parent, name);
    processEvent(new VFileCreateEvent(requestor, parent, name, false, null, null, false, null));
    VFileEvent caseSensitivityEvent = VirtualDirectoryImpl.generateCaseSensitivityChangedEventForUnknownCase(parent, name);
    if (caseSensitivityEvent != null) {
      processEvent(caseSensitivityEvent);
    }

    VirtualFile child = parent.findChild(name);
    if (child == null) {
      throw new IOException("Cannot create child file '" + name + "' at " + parent.getPath());
    }
    if (child.getCharset().equals(StandardCharsets.UTF_8) &&
        !(child.getFileType() instanceof InternalFileType) &&
        isUtf8BomRequired(child)) {
      child.setBOM(CharsetToolkit.UTF8_BOM);
    }
    return child;
  }

  private static boolean isUtf8BomRequired(@NotNull VirtualFile file) {
    for (Utf8BomOptionProvider encodingProvider : Utf8BomOptionProvider.EP_NAME.getIterable()) {
      if (encodingProvider.shouldAddBOMForNewUtf8File(file)) {
        return true;
      }
    }
    Project project = ProjectLocator.getInstance().guessProjectForFile(file);
    EncodingManager encodingManager = project == null ? EncodingManager.getInstance() : EncodingProjectManager.getInstance(project);
    return encodingManager.shouldAddBOMForNewUtf8File();
  }

  @Override
  public void deleteFile(Object requestor, @NotNull VirtualFile file) throws IOException {
    NewVirtualFileSystem fs = getFileSystem(file);
    fs.deleteFile(requestor, file);
    if (!fs.exists(file)) {
      processEvent(new VFileDeleteEvent(requestor, file, false));
    }
  }

  @Override
  public void renameFile(Object requestor, @NotNull VirtualFile file, @NotNull String newName) throws IOException {
    getFileSystem(file).renameFile(requestor, file, newName);
    String oldName = file.getName();
    if (!newName.equals(oldName)) {
      processEvent(new VFilePropertyChangeEvent(requestor, file, VirtualFile.PROP_NAME, oldName, newName, false));
    }
  }

  @Override
  public byte @NotNull [] contentsToByteArray(@NotNull VirtualFile file) throws IOException {
    return contentsToByteArray(file, true);
  }

  @Override
  public byte @NotNull [] contentsToByteArray(@NotNull VirtualFile file, boolean cacheContent) throws IOException {
    InputStream contentStream;
    boolean outdated;
    int fileId;
    long length;

    myInputLock.readLock().lock();
    try {
      fileId = getFileId(file);
      length = getLengthIfUpToDate(file);
      outdated = length == -1 || mustReloadContent(file);
      contentStream = outdated ? null : readContent(file);
    }
    finally {
      myInputLock.readLock().unlock();
    }

    if (contentStream == null) {
      NewVirtualFileSystem fs = getFileSystem(file);

      byte[] content;
      if (outdated) {
        // In this case, the file can have an out-of-date length. So, update it first (needed for the correct work of `contentsToByteArray`)
        // See IDEA-90813 for possible bugs.
        setLength(fileId, fs.getLength(file));
        content = fs.contentsToByteArray(file);
      }
      else {
        // a bit of optimization
        content = fs.contentsToByteArray(file);
        setLength(fileId, content.length);
      }

      // we should cache every local file's content, because the local history feature and Perforce offline mode depend on the cache
      // (do not cache archive content unless explicitly asked)
      if ((!fs.isReadOnly() || cacheContent && !app.isInternal() && !app.isUnitTestMode()) && shouldCache(content.length)) {
        myInputLock.writeLock().lock();
        try {
          writeContent(file, ByteArraySequence.create(content), fs.isReadOnly());
          setFlag(file, Flags.MUST_RELOAD_CONTENT, false);
        }
        finally {
          myInputLock.writeLock().unlock();
        }
      }

      return content;
    }

    try {
      assert length >= 0 : file;
      return contentStream.readNBytes((int)length);
    }
    catch (IOException e) {
      FSRecords.handleError(e);
      return ArrayUtil.EMPTY_BYTE_ARRAY;
    }
  }

  @Override
  public byte @NotNull [] contentsToByteArray(int contentId) throws IOException {
    //noinspection resource
    return readContentById(contentId).readAllBytes();
  }

  @Override
  public @NotNull InputStream getInputStream(@NotNull VirtualFile file) throws IOException {
    InputStream contentStream;
    boolean useReplicator = false;
    long len = 0L;
    boolean readOnly = false;

    myInputLock.readLock().lock();
    try {
      long storedLength = getLengthIfUpToDate(file);
      boolean mustReloadLength = storedLength == -1;

      if (mustReloadLength || mustReloadContent(file) || FileUtilRt.isTooLarge(file.getLength()) || (contentStream = readContent(file)) == null) {
        NewVirtualFileSystem fs = getFileSystem(file);
        len = mustReloadLength ? reloadLengthFromFS(file, fs) : storedLength;
        contentStream = fs.getInputStream(file);

        if (shouldCache(len)) {
          useReplicator = true;
          readOnly = fs.isReadOnly();
        }
      }
    }
    finally {
      myInputLock.readLock().unlock();
    }

    if (useReplicator) {
      contentStream = createReplicatorAndStoreContent(file, contentStream, len, readOnly);
    }

    return contentStream;
  }

  private static boolean shouldCache(long len) {
    return len <= PersistentFSConstants.FILE_LENGTH_TO_CACHE_THRESHOLD && !HeavyProcessLatch.INSTANCE.isRunning();
  }

  private static boolean mustReloadContent(@NotNull VirtualFile file) {
    return BitUtil.isSet(FSRecords.getFlags(getFileId(file)), Flags.MUST_RELOAD_CONTENT);
  }

  private static long reloadLengthFromFS(@NotNull VirtualFile file, @NotNull FileSystemInterface fs) {
    final long len = fs.getLength(file);
    int fileId = getFileId(file);
    setLength(fileId, len);
    return len;
  }

  private static void setLength(int fileId, long len) {
    FSRecords.setLength(fileId, len);
    setFlag(fileId, Flags.MUST_RELOAD_LENGTH, false);
  }

  private @NotNull InputStream createReplicatorAndStoreContent(@NotNull VirtualFile file,
                                                               @NotNull InputStream nativeStream,
                                                               long fileLength,
                                                               boolean readOnly) {
    if (nativeStream instanceof BufferExposingByteArrayInputStream byteStream) {
      // optimization
      byte[] bytes = byteStream.getInternalBuffer();
      storeContentToStorage(fileLength, file, readOnly, bytes, bytes.length);
      return nativeStream;
    }
    BufferExposingByteArrayOutputStream cache = new BufferExposingByteArrayOutputStream((int)fileLength);
    return new ReplicatorInputStream(nativeStream, cache) {
      boolean isClosed;

      @Override
      public void close() throws IOException {
        if (!isClosed) {
          try {
            boolean isEndOfFileReached;
            try {
              isEndOfFileReached = available() < 0 || read() == -1;
            }
            catch (IOException ignored) {
              isEndOfFileReached = false;
            }
            super.close();
            if (isEndOfFileReached) {
              storeContentToStorage(fileLength, file, readOnly, cache.getInternalBuffer(), cache.size());
            }
          }
          finally {
            isClosed = true;
          }
        }
      }
    };
  }

  private void storeContentToStorage(long fileLength,
                                     @NotNull VirtualFile file,
                                     boolean readOnly,
                                     byte @NotNull [] bytes,
                                     int byteLength) {
    myInputLock.writeLock().lock();
    try {
      if (byteLength == fileLength) {
        writeContent(file, new ByteArraySequence(bytes, 0, byteLength), readOnly);
        setFlag(file, Flags.MUST_RELOAD_CONTENT, false);
        setFlag(file, Flags.MUST_RELOAD_LENGTH, false);
      }
      else {
        doCleanPersistedContent(getFileId(file));
      }
    }
    finally {
      myInputLock.writeLock().unlock();
    }
  }

  @TestOnly
  public static byte @Nullable [] getContentHashIfStored(@NotNull VirtualFile file) {
    return FSRecords.getContentHash(getFileId(file));
  }

  // returns last recorded length or -1 if it must reload from FS
  private static long getLengthIfUpToDate(@NotNull VirtualFile file) {
    int fileId = getFileId(file);
    return BitUtil.isSet(FSRecords.getFlags(fileId), Flags.MUST_RELOAD_LENGTH) ? -1 : FSRecords.getLength(fileId);
  }

  @Override
  public @NotNull OutputStream getOutputStream(@NotNull VirtualFile file, Object requestor, long modStamp, long timeStamp) {
    return new ByteArrayOutputStream() {
      private boolean closed; // protection against user calling .close() twice

      @Override
      public void close() throws IOException {
        if (closed) return;
        super.close();

        app.assertWriteAccessAllowed();

        long oldLength = getLastRecordedLength(file);
        VFileContentChangeEvent event = new VFileContentChangeEvent(
          requestor, file, file.getModificationStamp(), modStamp, file.getTimeStamp(), -1, oldLength, count, false);
        List<VFileEvent> events = List.of(event);
        fireBeforeEvents(getPublisher(), events);
        getVfsLog().getVFileEventApplicationListener().beforeApply(event);
        IOException exception = null;

        NewVirtualFileSystem fs = getFileSystem(file);
        // `FSRecords.ContentOutputStream` already buffered, no need to wrap in `BufferedStream`
        try (OutputStream persistenceStream = writeContent(file, fs.isReadOnly())) {
          persistenceStream.write(buf, 0, count);
        }
        catch (IOException e) {
          exception = e;
          throw e;
        }
        finally {
          try (OutputStream ioFileStream = fs.getOutputStream(file, requestor, modStamp, timeStamp)) {
            ioFileStream.write(buf, 0, count);
          }
          catch (IOException e) {
            exception = Suppressions.addSuppressed(exception, e);
            throw exception;
          }
          finally {
            closed = true;

            FileAttributes attributes = fs.getAttributes(file);
            // due to FS rounding, the timestamp of the file can significantly differ from the current time
            long newTimestamp = attributes != null ? attributes.lastModified : DEFAULT_TIMESTAMP;
            long newLength = attributes != null ? attributes.length : DEFAULT_LENGTH;
            executeTouch(file, false, event.getModificationStamp(), newLength, newTimestamp);
            getVfsLog().getVFileEventApplicationListener().afterApply(event, exception);
            fireAfterEvents(getPublisher(), events);
          }
        }
      }
    };
  }

  @Override
  public int acquireContent(@NotNull VirtualFile file) {
    return FSRecords.acquireFileContent(getFileId(file));
  }

  @Override
  public void releaseContent(int contentId) {
    FSRecords.releaseContent(contentId);
  }

  @Override
  public int getCurrentContentId(@NotNull VirtualFile file) {
    return FSRecords.getContentId(getFileId(file));
  }

  public boolean isOwnData(@NotNull VfsData data) {
    return data == myVfsData;
  }

  @Override
  public void moveFile(Object requestor, @NotNull VirtualFile file, @NotNull VirtualFile newParent) throws IOException {
    getFileSystem(file).moveFile(requestor, file, newParent);
    processEvent(new VFileMoveEvent(requestor, file, newParent));
  }

  private void processEvent(@NotNull VFileEvent event) {
    app.assertWriteAccessAllowed();
    if (!event.isValid()) return;

    List<VFileEvent> outValidatedEvents = new ArrayList<>();
    outValidatedEvents.add(event);
    List<Runnable> outApplyActions = new ArrayList<>();
    List<VFileEvent> jarDeleteEvents = VfsImplUtil.getJarInvalidationEvents(event, outApplyActions);
    BulkFileListener publisher = getPublisher();
    if (jarDeleteEvents.isEmpty() && outApplyActions.isEmpty()) {
      // optimisation: skip all groupings
      runSuppressing(
        () -> fireBeforeEvents(publisher, outValidatedEvents),
        () -> applyEvent(event),
        () -> fireAfterEvents(publisher, outValidatedEvents)
      );
    }
    else {
      outApplyActions.add(() -> applyEvent(event));
      // there are a number of additional jar events generated
      for (VFileEvent jarDeleteEvent : jarDeleteEvents) {
        outApplyActions.add(() -> applyEvent(jarDeleteEvent));
        outValidatedEvents.add(jarDeleteEvent);
      }
      applyMultipleEvents(publisher, outApplyActions, outValidatedEvents, false);
    }
  }

  private static void runSuppressing(@NotNull Runnable r1, @NotNull Runnable r2, @NotNull Runnable r3) {
    Throwable t = null;
    try {
      r1.run();
    }
    catch (Throwable e) {
      t = e;
    }
    try {
      r2.run();
    }
    catch (Throwable e) {
      if (t == null) {
        t = e;
      }
      else {
        t.addSuppressed(e);
      }
    }
    try {
      r3.run();
    }
    catch (Throwable e) {
      if (t == null) {
        t = e;
      }
      else {
        t.addSuppressed(e);
      }
    }
    if (t != null) {
      ExceptionUtilRt.rethrowUnchecked(t);
    }
  }

  // Tries to find a group of non-conflicting events in range [`startIndex`..`inEvents.size()`).
  // Two events are conflicting if the originating file of one event is an ancestor (non-strict) of the file from the other.
  // E.g., "change(a/b/c/x.txt)" and "delete(a/b/c)" are conflicting because "a/b/c/x.txt" is under the "a/b/c" directory from the other event.
  //
  // returns index after the last grouped event.
  private static int groupByPath(@NotNull List<? extends CompoundVFileEvent> events,
                                 int startIndex,
                                 @NotNull MostlySingularMultiMap<String, VFileEvent> filesInvolved,
                                 @NotNull Set<? super String> middleDirsInvolved,
                                 @NotNull Set<? super VirtualFile> deleted,
                                 @NotNull Map<VirtualDirectoryImpl, Object> toCreate,// dir -> VFileCreateEvent|Collection<VFileCreateEvent> in this dir
                                 @NotNull Set<? super VFileEvent> eventsToRemove) {
    // storing all paths from all events (including all parents),
    // checking each new event's path against this set, and if it's there, this event is conflicting
    int i;
    for (i = startIndex; i < events.size(); i++) {
      VFileEvent event = events.get(i).getFileEvent();
      String path = event.getPath();
      if (event instanceof VFileDeleteEvent && removeNestedDelete(((VFileDeleteEvent)event).getFile(), deleted)) {
        eventsToRemove.add(event);
        continue;
      }
      if (event instanceof VFileCreateEvent createEvent) {
        VirtualDirectoryImpl parent = (VirtualDirectoryImpl)createEvent.getParent();
        Object createEvents = toCreate.get(parent);
        if (createEvents == null) {
          toCreate.put(parent, createEvent);
        }
        else {
          if (createEvents instanceof VFileCreateEvent prevEvent) {
            Set<VFileCreateEvent> children = parent.isCaseSensitive() ? new LinkedHashSet<>() : CollectionFactory.createLinkedCustomHashingStrategySet(CASE_INSENSITIVE_STRATEGY);
            children.add(prevEvent);
            toCreate.put(parent, children);
            createEvents = children;
          }
          //noinspection unchecked
          Collection<VFileCreateEvent> children = (Collection<VFileCreateEvent>)createEvents;
          if (!children.add(createEvent)) {
            eventsToRemove.add(createEvent);
            continue;
          }
        }
      }

      if (eventConflictsWithPrevious(event, path, filesInvolved, middleDirsInvolved)) {
        break;
      }
      // Some events are composite (e.g. `VFileMoveEvent` = `VFileDeleteEvent` + `VFileCreateEvent`),
      // so both paths should be checked for conflicts.
      String path2 = getAlternativePath(event);
      if (path2 != null &&
          !(SystemInfoRt.isFileSystemCaseSensitive ? path2.equals(path) : path2.equalsIgnoreCase(path)) &&
          eventConflictsWithPrevious(event, path2, filesInvolved, middleDirsInvolved)) {
        break;
      }
    }

    return i;
  }

  private static String getAlternativePath(@NotNull VFileEvent event) {
    if (event instanceof VFilePropertyChangeEvent pce && ((VFilePropertyChangeEvent)event).getPropertyName().equals(VirtualFile.PROP_NAME)) {
      VirtualFile parent = pce.getFile().getParent();
      String newName = (String)pce.getNewValue();
      return parent == null ? newName : parent.getPath()+"/"+newName;
    }
    if (event instanceof VFileCopyEvent) {
      return ((VFileCopyEvent)event).getFile().getPath();
    }
    if (event instanceof VFileMoveEvent vme) {
      String newName = vme.getFile().getName();
      return vme.getNewParent().getPath() + "/" + newName;
    }
    return null;
  }

  // return true if the file or the ancestor of the file is going to be deleted
  private static boolean removeNestedDelete(@NotNull VirtualFile file, @NotNull Set<? super VirtualFile> deleted) {
    if (!deleted.add(file)) {
      return true;
    }
    while (true) {
      file = file.getParent();
      if (file == null) break;
      if (deleted.contains(file)) {
        return true;
      }
    }
    return false;
  }

  private static boolean eventConflictsWithPrevious(@NotNull VFileEvent event,
                                                    @NotNull String path,
                                                    @NotNull MostlySingularMultiMap<String, VFileEvent> files,
                                                    @NotNull Set<? super String> middleDirs) {
    boolean canReconcileEvents = true;
    for (VFileEvent prev : files.get(path)) {
      if (!(isContentChangeLikeHarmlessEvent(event) && isContentChangeLikeHarmlessEvent(prev))) {
        canReconcileEvents = false;
        break;
      }
    }
    if (!canReconcileEvents) {
      // conflicting event found for (non-strict) descendant, stop
      return true;
    }
    if (middleDirs.contains(path)) {
      // conflicting event found for (non-strict) descendant, stop
      return true;
    }
    files.add(path, event);
    int li = path.length();
    while (true) {
      int liPrev = path.lastIndexOf('/', li-1);
      if (liPrev == -1) break;
      String parentDir = path.substring(0, liPrev);
      if (files.containsKey(parentDir)) {
        // conflicting event found for the ancestor, stop
        return true;
      }
      if (!middleDirs.add(parentDir)) break;  // all parents are already stored; stop
      li = liPrev;
    }

    return false;
  }

  private static boolean isContentChangeLikeHarmlessEvent(@NotNull VFileEvent event) {
    if (event instanceof VFileContentChangeEvent) return true;
    if (event instanceof VFilePropertyChangeEvent) {
      String p = ((VFilePropertyChangeEvent)event).getPropertyName();
      return p.equals(VirtualFile.PROP_WRITABLE) || p.equals(VirtualFile.PROP_ENCODING) || p.equals(VirtualFile.PROP_CHILDREN_CASE_SENSITIVITY);
    }
    return false;
  }

  // Finds a group of non-conflicting events and validates them.
  // The `outApplyActions` will contain handlers for applying grouped events.
  // The `outValidatedEvents` will contain events for which `VFileEvent#isValid` is true.
  //
  // return index after the last processed event
  private int groupAndValidate(@NotNull List<? extends CompoundVFileEvent> events,
                               int startIndex,
                               @NotNull List<? super Runnable> outApplyActions,
                               @NotNull List<? super VFileEvent> outValidatedEvents,
                               @NotNull MostlySingularMultiMap<String, VFileEvent> filesInvolved,
                               @NotNull Set<? super String> middleDirsInvolved,
                               @NotNull Map<VirtualDirectoryImpl, Object> toCreate,
                               @NotNull Set<VFileEvent> toIgnore,
                               @NotNull Set<? super VirtualFile> toDelete,
                               boolean excludeAsyncListeners) {
    int endIndex = groupByPath(events, startIndex, filesInvolved, middleDirsInvolved, toDelete, toCreate, toIgnore);
    assert endIndex > startIndex : events.get(startIndex) +"; files: "+filesInvolved+"; middleDirs: "+middleDirsInvolved;
    // since all events in the group events[`startIndex`..`endIndex`) are mutually non-conflicting, we can re-arrange creations/deletions together
    groupCreations(outValidatedEvents, outApplyActions, toCreate);
    groupDeletions(events, startIndex, endIndex, outValidatedEvents, outApplyActions, toIgnore);
    groupOthers(events, startIndex, endIndex, outValidatedEvents, outApplyActions);

    for (int i = startIndex; i < endIndex; i++) {
      CompoundVFileEvent event = events.get(i);

      outApplyActions.addAll(event.getApplyActions());

      if (excludeAsyncListeners && !event.areInducedEventsCalculated()) {
        LOG.error("Nested file events must be processed by async file listeners! Event: " + event);
      }

      for (VFileEvent jarDeleteEvent : event.getInducedEvents()) {
        outApplyActions.add((Runnable)() -> applyEvent(jarDeleteEvent));
        outValidatedEvents.add(jarDeleteEvent);
      }
    }
    return endIndex;
  }

  // Finds all `VFileCreateEvent` instances in [`start`..`end`), groups them by parent directory, validates in bulk for each directory,
  // and returns `applyCreations()` runnable
  private void groupCreations(@NotNull List<? super VFileEvent> outValidated,
                              @NotNull List<? super Runnable> outApplyActions,
                              @NotNull Map<VirtualDirectoryImpl, Object> created) {
    if (!created.isEmpty()) {
      // since the VCreateEvent.isValid() is extremely expensive, combine all creation events for the directory
      // and use VirtualDirectoryImpl.validateChildrenToCreate() optimised for bulk validation
      boolean hasValidEvents = false;
      for (Map.Entry<VirtualDirectoryImpl, Object> entry : created.entrySet()) {
        VirtualDirectoryImpl directory = entry.getKey();
        Object value = entry.getValue();
        //noinspection unchecked
        Set<VFileCreateEvent> createEvents =
          value instanceof VFileCreateEvent ? new HashSet<>(List.of((VFileCreateEvent)value)) : (Set<VFileCreateEvent>)value;
        directory.validateChildrenToCreate(createEvents);
        hasValidEvents |= !createEvents.isEmpty();
        outValidated.addAll(createEvents);
        entry.setValue(createEvents);
      }

      if (hasValidEvents) {
        //noinspection unchecked,rawtypes
        Map<VirtualDirectoryImpl, Set<VFileCreateEvent>> finalGrouped = (Map)created;
        outApplyActions.add((Runnable)() -> {
          applyCreations(finalGrouped);
          incStructuralModificationCount();
        });
      }
    }
  }

  // Finds all `VFileDeleteEvent` instances in [`start`..`end`), groups them by parent directory (can be null),
  // filters out files which parent dir is to be deleted too, and returns `applyDeletions()` runnable.
  private void groupDeletions(@NotNull List<? extends CompoundVFileEvent> events,
                              int start,
                              int end,
                              @NotNull List<? super VFileEvent> outValidated,
                              @NotNull List<? super Runnable> outApplyActions,
                              @NotNull Set<? extends VFileEvent> toIgnore) {
    MultiMap<VirtualDirectoryImpl, VFileDeleteEvent> grouped = null;
    boolean hasValidEvents = false;
    for (int i = start; i < end; i++) {
      VFileEvent event = events.get(i).getFileEvent();
      if (!(event instanceof VFileDeleteEvent de) || toIgnore.contains(event) || !event.isValid()) continue;
      VirtualDirectoryImpl parent = (VirtualDirectoryImpl)de.getFile().getParent();
      if (grouped == null) {
        grouped = new MultiMap<>(end - start);
      }
      grouped.putValue(parent, de);
      outValidated.add(event);
      hasValidEvents = true;
    }

    if (hasValidEvents) {
      MultiMap<VirtualDirectoryImpl, VFileDeleteEvent> finalGrouped = grouped;
      outApplyActions.add((Runnable)() -> {
        clearIdCache();
        applyDeletions(finalGrouped);
        incStructuralModificationCount();
      });
    }
  }

  // Finds events other than `VFileCreateEvent` or `VFileDeleteEvent` in [`start`..`end`), validates,
  // and returns `applyEvent()` runnable for each event because it's assumed there won't be too many of them.
  private void groupOthers(@NotNull List<? extends CompoundVFileEvent> events,
                           int start,
                           int end,
                           @NotNull List<? super VFileEvent> outValidated,
                           @NotNull List<? super Runnable> outApplyActions) {
    for (int i = start; i < end; i++) {
      VFileEvent event = events.get(i).getFileEvent();
      if (event instanceof VFileCreateEvent || event instanceof VFileDeleteEvent || !event.isValid()) continue;
      outValidated.add(event);
      outApplyActions.add((Runnable)() -> applyEvent(event));
    }
  }

  private static final int INNER_ARRAYS_THRESHOLD = 1024; // max initial size, to avoid OOM on million-events processing

  @ApiStatus.Internal
  public void processEventsImpl(@NotNull List<? extends CompoundVFileEvent> events, boolean excludeAsyncListeners) {
    app.assertWriteAccessAllowed();

    int startIndex = 0;
    int cappedInitialSize = Math.min(events.size(), INNER_ARRAYS_THRESHOLD);
    List<Runnable> applyActions = new ArrayList<>(cappedInitialSize);
    // even in the unlikely case when case-insensitive maps falsely detect conflicts of case-sensitive paths,
    // the worst outcome will be one extra event batch, which is acceptable
    MostlySingularMultiMap<String, VFileEvent> files = new MostlySingularMultiMap<>(CollectionFactory.createFilePathMap(cappedInitialSize));
    Set<String> middleDirs = CollectionFactory.createFilePathSet(cappedInitialSize);

    List<VFileEvent> validated = new ArrayList<>(cappedInitialSize);
    BulkFileListener publisher = getPublisher();
    Map<VirtualDirectoryImpl, Object> toCreate = new LinkedHashMap<>();
    Set<VFileEvent> toIgnore = new ReferenceOpenHashSet<>(); // VFileEvent overrides equals(), hence identity-based
    Set<VirtualFile> toDelete = CollectionFactory.createSmallMemoryFootprintSet();
    while (startIndex != events.size()) {
      PingProgress.interactWithEdtProgress();

      applyActions.clear();
      files.clear();
      middleDirs.clear();
      validated.clear();
      toCreate.clear();
      toIgnore.clear();
      toDelete.clear();
      startIndex = groupAndValidate(events, startIndex, applyActions, validated, files, middleDirs, toCreate, toIgnore, toDelete, excludeAsyncListeners);

      if (!validated.isEmpty()) {
        applyMultipleEvents(publisher, applyActions, validated, excludeAsyncListeners);
      }
    }
  }

  private static void applyMultipleEvents(@NotNull BulkFileListener publisher,
                                          @NotNull List<? extends @NotNull Runnable> applyActions,
                                          @NotNull List<? extends @NotNull VFileEvent> applyEvents,
                                          boolean excludeAsyncListeners) {
    PingProgress.interactWithEdtProgress();
    // defensive copying to cope with ill-written listeners that save the passed list for later processing
    List<VFileEvent> toSend = List.of(applyEvents.toArray(new VFileEvent[0]));
    Throwable x = null;

    try {
      if (excludeAsyncListeners) AsyncEventSupport.markAsynchronouslyProcessedEvents(toSend);

      try {
        fireBeforeEvents(publisher, toSend);
      }
      catch (Throwable t) {
        x = t;
      }

      PingProgress.interactWithEdtProgress();
      for (Runnable runnable : applyActions) {
        try {
          runnable.run();
        }
        catch (Throwable t) {
          if (x != null) t.addSuppressed(x);
          x = t;
        }
      }

      PingProgress.interactWithEdtProgress();
      try {
        fireAfterEvents(publisher, toSend);
      }
      catch (Throwable t) {
        if (x != null) t.addSuppressed(x);
        x = t;
      }
    }
    finally {
      if (excludeAsyncListeners) AsyncEventSupport.unmarkAsynchronouslyProcessedEvents(toSend);
      if (x != null) ExceptionUtil.rethrow(x);
    }
  }

  private static void fireBeforeEvents(@NotNull BulkFileListener publisher, @NotNull List<? extends VFileEvent> toSend) {
    runSuppressing(
      () -> publisher.before(toSend),
      () -> ((BulkFileListener)VirtualFilePointerManager.getInstance()).before(toSend),
      ()->{}
    );
  }

  private static void fireAfterEvents(@NotNull BulkFileListener publisher, @NotNull List<? extends VFileEvent> toSend) {
    runSuppressing(
      () -> CachedFileType.clearCache(),
      () -> ((BulkFileListener)VirtualFilePointerManager.getInstance()).after(toSend),
      () -> publisher.after(toSend)
    );
  }

  // remove children from specified directories using VirtualDirectoryImpl.removeChildren() optimised for bulk removals
  private void applyDeletions(@NotNull MultiMap<VirtualDirectoryImpl, VFileDeleteEvent> deletions) {
    for (Map.Entry<VirtualDirectoryImpl, Collection<VFileDeleteEvent>> entry : deletions.entrySet()) {
      VirtualDirectoryImpl parent = entry.getKey();
      Collection<VFileDeleteEvent> deleteEvents = entry.getValue();
      // no valid containing directory; applying events the old way - one by one
      if (parent == null || !parent.isValid()) {
        deleteEvents.forEach(this::applyEvent);
        return;
      }

      int parentId = getFileId(parent);
      List<CharSequence> childrenNamesDeleted = new ArrayList<>(deleteEvents.size());
      IntSet childrenIdsDeleted = new IntOpenHashSet(deleteEvents.size());
      List<ChildInfo> deleted = new ArrayList<>(deleteEvents.size());
      for (VFileDeleteEvent event : deleteEvents) {
        VirtualFile file = event.getFile();
        int id = getFileId(file);
        childrenNamesDeleted.add(file.getNameSequence());
        childrenIdsDeleted.add(id);
        FSRecords.deleteRecordRecursively(id);
        invalidateSubtree(file, "Bulk file deletions", event);
        deleted.add(new ChildInfoImpl(id, ChildInfoImpl.UNKNOWN_ID_YET, null, null, null));
      }
      deleted.sort(ChildInfo.BY_ID);
      FSRecords.update(parent, parentId, oldChildren -> oldChildren.subtract(deleted));
      parent.removeChildren(childrenIdsDeleted, childrenNamesDeleted);
    }
  }

  // add children to specified directories using VirtualDirectoryImpl.createAndAddChildren() optimised for bulk additions
  private void applyCreations(@NotNull Map<VirtualDirectoryImpl, Set<VFileCreateEvent>> creations) {
    for (Map.Entry<VirtualDirectoryImpl, Set<VFileCreateEvent>> entry : creations.entrySet()) {
      VirtualDirectoryImpl parent = entry.getKey();
      Collection<VFileCreateEvent> createEvents = entry.getValue();
      applyCreateEventsInDirectory(parent, createEvents);
    }
  }

  private void applyCreateEventsInDirectory(@NotNull VirtualDirectoryImpl parent, @NotNull Collection<? extends VFileCreateEvent> createEvents) {
    int parentId = getFileId(parent);
    NewVirtualFile vf = findFileById(parentId);
    if (!(vf instanceof VirtualDirectoryImpl)) return;
    parent = (VirtualDirectoryImpl)vf;  // retain in `myIdToDirCache` at least for the duration of this block, so that subsequent `findFileById` won't crash
    NewVirtualFileSystem fs = getFileSystem(parent);

    List<ChildInfo> childrenAdded = new ArrayList<>(createEvents.size());
    for (VFileCreateEvent createEvent : createEvents) {
      createEvent.resetCache();
      String name = createEvent.getChildName();
      Pair<@NotNull FileAttributes, String> childData =
        getChildData(fs, createEvent.getParent(), name, createEvent.getAttributes(), createEvent.getSymlinkTarget());
      if (childData != null) {
        ChildInfo child = makeChildRecord(parent, parentId, name, childData, fs, createEvent.getChildren());
        childrenAdded.add(child);
      }
    }
    childrenAdded.sort(ChildInfo.BY_ID);
    boolean caseSensitive = parent.isCaseSensitive();
    FSRecords.update(parent, parentId, oldChildren -> oldChildren.merge(childrenAdded, caseSensitive));
    parent.createAndAddChildren(childrenAdded, false, (__,___)->{});

    saveScannedChildrenRecursively(createEvents, fs, parent.isCaseSensitive());
  }

  private static void saveScannedChildrenRecursively(@NotNull Collection<? extends VFileCreateEvent> createEvents,
                                                     @NotNull NewVirtualFileSystem fs,
                                                     boolean isCaseSensitive) {
    for (VFileCreateEvent createEvent : createEvents) {
      ChildInfo[] children = createEvent.getChildren();
      if (children == null || !createEvent.isDirectory()) continue;
      // todo avoid expensive findFile
      VirtualFile createdDir = createEvent.getFile();
      if (createdDir instanceof VirtualDirectoryImpl) {
        Queue<Pair<VirtualDirectoryImpl, ChildInfo[]>> queue = new ArrayDeque<>();
        queue.add(new Pair<>((VirtualDirectoryImpl)createdDir, children));
        while (!queue.isEmpty()) {
          Pair<VirtualDirectoryImpl, ChildInfo[]> queued = queue.remove();
          VirtualDirectoryImpl directory = queued.first;
          List<ChildInfo> scannedChildren = Arrays.asList(queued.second);
          int directoryId = directory.getId();
          List<ChildInfo> added = new ArrayList<>(scannedChildren.size());
          for (ChildInfo childInfo : scannedChildren) {
            CharSequence childName = childInfo.getName();
            Pair<@NotNull FileAttributes, String> childData =
              getChildData(fs, directory, childName.toString(), childInfo.getFileAttributes(), childInfo.getSymlinkTarget());
            if (childData != null) {
              added.add(makeChildRecord(directory, directoryId, childName, childData, fs, childInfo.getChildren()));
            }
          }

          added.sort(ChildInfo.BY_ID);
          FSRecords.update(directory, directoryId, oldChildren -> oldChildren.merge(added, isCaseSensitive));
          setChildrenCached(directoryId);
          // set "all children loaded" because the first "fileCreated" listener (looking at you, local history)
          // will call getChildren() anyway, beyond a shadow of a doubt
          directory.createAndAddChildren(added, true, (childCreated, childInfo) -> {
            // enqueue recursive children
            if (childCreated instanceof VirtualDirectoryImpl && childInfo.getChildren() != null) {
              queue.add(new Pair<>((VirtualDirectoryImpl)childCreated, childInfo.getChildren()));
            }
          });
        }
      }
    }
  }

  @Override
  public @Nullable VirtualFileSystemEntry findRoot(@NotNull String path, @NotNull NewVirtualFileSystem fs) {
    if (!myConnected.get()) {
      LOG.info("VFS disconnected. Can't provide root for " + path + " in " + fs);
      return null;
    }
    if (path.isEmpty()) {
      LOG.error("Invalid root, fs=" + fs);
      return null;
    }

    String rootUrl = UriUtil.trimTrailingSlashes(VirtualFileManager.constructUrl(fs.getProtocol(), path));
    VirtualFileSystemEntry root = myRoots.get(rootUrl);
    if (root != null) return root;

    String rootName;
    String rootPath;
    FileAttributes attributes;
    if (fs instanceof ArchiveFileSystem afs) {
      VirtualFile localFile = afs.findLocalByRootPath(path);
      if (localFile == null) return null;
      rootName = localFile.getName();
      rootPath = afs.getRootPathByLocal(localFile);
      rootUrl = UriUtil.trimTrailingSlashes(VirtualFileManager.constructUrl(fs.getProtocol(), rootPath));
      attributes = afs.getArchiveRootAttributes(new StubVirtualFile(fs) {
        @Override public @NotNull String getPath() { return rootPath; }
        @Override public @Nullable VirtualFile getParent() { return null; }
      });
    }
    else {
      rootName = rootPath = path;
      attributes = loadAttributes(fs, rootPath);
    }

    if (attributes == null || !attributes.isDirectory()) {
      return null;
    }
    // assume roots have the FS default case sensitivity
    attributes = attributes.withCaseSensitivity(fs.isCaseSensitive() ? FileAttributes.CaseSensitivity.SENSITIVE : FileAttributes.CaseSensitivity.INSENSITIVE);
    // avoid creating gazillions of roots which are not actual roots
    String parentPath;
    if (fs instanceof LocalFileSystem && !(parentPath = PathUtil.getParentPath(rootPath)).isEmpty()) {
      FileAttributes parentAttributes = loadAttributes(fs, parentPath);
      if (parentAttributes != null) {
        throw new IllegalArgumentException("Must pass FS root path, but got: '" + path + "', which has a parent '" + parentPath + "'." +
                                           " Use NewVirtualFileSystem.extractRootPath() for obtaining root path");
      }
    }

    int rootId = FSRecords.findRootRecord(rootUrl);
    FSRecords.loadRootData(rootId, path, fs);

    int rootNameId = FileNameCache.storeName(rootName);
    boolean mark;
    VirtualFileSystemEntry newRoot;
    synchronized (myRoots) {
      root = myRoots.get(rootUrl);
      if (root != null) return root;

      try {
        String pathBeforeSlash = UriUtil.trimTrailingSlashes(rootPath);
        newRoot = new FsRoot(rootId, rootNameId, myVfsData, fs, pathBeforeSlash, attributes, path);
      }
      catch (VfsData.FileAlreadyCreatedException e) {
        for (Map.Entry<String, VirtualFileSystemEntry> entry : myRoots.entrySet()) {
          VirtualFileSystemEntry existingRoot = entry.getValue();
          if (existingRoot.getId() == rootId) {
            String message = "Duplicate FS roots: " + rootUrl + " / " + entry.getKey() + " id=" + rootId + " valid=" + existingRoot.isValid();
            throw new RuntimeException(message, e);
          }
        }
        throw new RuntimeException("No root duplication, rootName='" + rootName + "'; rootNameId=" + rootNameId + "; rootId=" + rootId + ";" +
                                   " path='" + path + "'; fs=" + fs + "; rootUrl='" + rootUrl + "'", e);
      }
      incStructuralModificationCount();
      mark = writeAttributesToRecord(rootId, 0, rootName, fs.isCaseSensitive(), attributes, false) != -1;

      myRoots.put(rootUrl, newRoot);
      myIdToDirCache.cacheDir(newRoot);
    }

    if (!mark && attributes.lastModified != FSRecords.getTimestamp(rootId)) {
      newRoot.markDirtyRecursively();
    }

    LOG.assertTrue(rootId == newRoot.getId(), "root=" + newRoot + " expected=" + rootId + " actual=" + newRoot.getId());

    return newRoot;
  }

  private static @Nullable FileAttributes loadAttributes(@NotNull NewVirtualFileSystem fs, @NotNull String path) {
    return fs.getAttributes(new StubVirtualFile(fs) {
      @Override public @NotNull String getPath() { return path; }
      @Override public @Nullable VirtualFile getParent() { return null; }
    });
  }

  @Override
  public void clearIdCache() {
    // remove all except roots
    myIdToDirCache.dropNonRootCachedDirs();
  }

  @Override
  public @Nullable NewVirtualFile findFileById(int id) {
    VirtualFileSystemEntry cached = myIdToDirCache.getCachedDir(id);
    return cached != null ? cached : FSRecords.findFileById(id, myIdToDirCache);
  }

  @Override
  public VirtualFile @NotNull [] getRoots() {
    return VfsUtilCore.toVirtualFileArray(myRoots.values());
  }

  @Override
  public VirtualFile @NotNull [] getRoots(@NotNull NewVirtualFileSystem fs) {
    List<VirtualFile> roots = new ArrayList<>();

    for (NewVirtualFile root : myRoots.values()) {
      if (root.getFileSystem() == fs) {
        roots.add(root);
      }
    }

    return VfsUtilCore.toVirtualFileArray(roots);
  }

  @Override
  public VirtualFile @NotNull [] getLocalRoots() {
    List<VirtualFile> roots = new SmartList<>();

    for (NewVirtualFile root : myRoots.values()) {
      if (root.isInLocalFileSystem() && !(root.getFileSystem() instanceof TempFileSystem)) {
        roots.add(root);
      }
    }
    return VfsUtilCore.toVirtualFileArray(roots);
  }

  private void applyEvent(@NotNull VFileEvent event) {
    SlowOperations.allowSlowOperations(() -> doApplyEvent(event));
  }

  private void doApplyEvent(@NotNull VFileEvent event) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Applying " + event);
    }
    myVfsLog.getVFileEventApplicationListener().beforeApply(event);
    Throwable exception = null;
    try {
      if (event instanceof VFileCreateEvent) {
        VFileCreateEvent ce = (VFileCreateEvent)event;
        executeCreateChild(ce.getParent(), ce.getChildName(), ce.getAttributes(), ce.getSymlinkTarget(), ce.isEmptyDirectory());
      }
      else if (event instanceof VFileDeleteEvent deleteEvent) {
        executeDelete(deleteEvent);
      }
      else if (event instanceof VFileContentChangeEvent contentUpdateEvent) {
        VirtualFile file = contentUpdateEvent.getFile();
        long length = contentUpdateEvent.getNewLength();
        long timestamp = contentUpdateEvent.getNewTimestamp();

        if (!contentUpdateEvent.isLengthAndTimestampDiffProvided()) {
          NewVirtualFileSystem fs = getFileSystem(file);
          FileAttributes attributes = fs.getAttributes(file);
          length = attributes != null ? attributes.length : DEFAULT_LENGTH;
          timestamp = attributes != null ? attributes.lastModified : DEFAULT_TIMESTAMP;
        }

        executeTouch(file, contentUpdateEvent.isFromRefresh(), contentUpdateEvent.getModificationStamp(), length, timestamp);
      }
      else if (event instanceof VFileCopyEvent ce) {
        executeCreateChild(ce.getNewParent(), ce.getNewChildName(), null, null, ce.getFile().getChildren().length == 0);
      }
      else if (event instanceof VFileMoveEvent moveEvent) {
        executeMove(moveEvent.getFile(), moveEvent.getNewParent());
      }
      else if (event instanceof VFilePropertyChangeEvent propertyChangeEvent) {
        VirtualFile file = propertyChangeEvent.getFile();
        Object newValue = propertyChangeEvent.getNewValue();
        switch (propertyChangeEvent.getPropertyName()) {
          case VirtualFile.PROP_NAME -> executeRename(file, (String)newValue);
          case VirtualFile.PROP_WRITABLE -> {
            executeSetWritable(file, ((Boolean)newValue).booleanValue());
            if (LOG.isDebugEnabled()) {
              LOG.debug("File " + file + " writable=" + file.isWritable() + " id=" + getFileId(file));
            }
          }
          case VirtualFile.PROP_HIDDEN -> executeSetHidden(file, ((Boolean)newValue).booleanValue());
          case VirtualFile.PROP_SYMLINK_TARGET -> executeSetTarget(file, (String)newValue);
          case VirtualFile.PROP_CHILDREN_CASE_SENSITIVITY -> executeChangeCaseSensitivity(file, (FileAttributes.CaseSensitivity)newValue);
        }
      }
    }
    catch (ProcessCanceledException e) {
      exception = e;
      throw e;
    }
    catch (Exception e) {
      exception = e;
      LOG.error(e);
    }
    finally {
      myVfsLog.getVFileEventApplicationListener().afterApply(event, exception);
    }
  }

  @ApiStatus.Internal
  public static void executeChangeCaseSensitivity(@NotNull VirtualFile file, @NotNull FileAttributes.CaseSensitivity newCaseSensitivity) {
    VirtualDirectoryImpl directory = (VirtualDirectoryImpl)file;
    setFlag(directory, Flags.CHILDREN_CASE_SENSITIVE, newCaseSensitivity == FileAttributes.CaseSensitivity.SENSITIVE);
    setFlag(directory, Flags.CHILDREN_CASE_SENSITIVITY_CACHED, true);
    directory.setCaseSensitivityFlag(newCaseSensitivity);
  }

  @Override
  public String toString() {
    return "PersistentFS";
  }

  private void executeCreateChild(@NotNull VirtualFile parent,
                                  @NotNull String name,
                                  @Nullable FileAttributes attributes,
                                  @Nullable String symlinkTarget,
                                  boolean isEmptyDirectory) {
    NewVirtualFileSystem fs = getFileSystem(parent);
    int parentId = getFileId(parent);
    Pair<@NotNull FileAttributes, String> childData = getChildData(fs, parent, name, attributes, symlinkTarget);
    if (childData == null) {
      return;
    }
    ChildInfo childInfo = makeChildRecord(parent, parentId, name, childData, fs, null);
    FSRecords.update(parent, parentId, children -> {
      // check that names are not duplicated
      ChildInfo duplicate = findExistingChildInfo(parent, name, children.children);
      if (duplicate != null) return children;

      return children.insert(childInfo);
    });
    int childId = childInfo.getId();
    assert parent instanceof VirtualDirectoryImpl : parent;
    VirtualDirectoryImpl dir = (VirtualDirectoryImpl)parent;
    VirtualFileSystemEntry child = dir.createChild(name, childId, fileAttributesToFlags(childData.first), isEmptyDirectory);
    if (isEmptyDirectory) {
      // When creating an empty directory, we need to make sure every file created inside will fire a "file-created" event,
      // in order to `VirtualFilePointerManager` get those events to update its pointers properly
      // (because currently it ignores empty directory creation events for performance reasons).
      setChildrenCached(childId);
    }
    dir.addChild(child);
    incStructuralModificationCount();
  }

  @NotNull
  private static ChildInfo makeChildRecord(@NotNull VirtualFile parentFile,
                                           int parentId,
                                           @NotNull CharSequence name,
                                           @NotNull Pair<@NotNull FileAttributes, String> childData,
                                           @NotNull NewVirtualFileSystem fs,
                                           @NotNull ChildInfo @Nullable [] children) {
    FileAttributes attributes = childData.first;

    int childId = FSRecords.createRecord();
    int nameId = writeAttributesToRecord(childId, parentId, name, parentFile.isCaseSensitive(), attributes, true);

    assert childId > 0 : childId;
    if (attributes.isDirectory()) {
      FSRecords.loadDirectoryData(childId, parentFile, name, fs);
    }
    return new ChildInfoImpl(childId, nameId, attributes, children, childData.second);
  }

  public static void moveChildrenRecords(int fromParentId, int toParentId) {
    if (fromParentId == -1) return;

    for (ChildInfo childToMove : FSRecords.list(fromParentId).children) {
      FSRecords.setParent(childToMove.getId(), toParentId);
    }
    FSRecords.moveChildren(fromParentId, toParentId);
  }

  // return File attributes, symlink target
  // null when file not found
  private static @Nullable Pair<@NotNull FileAttributes, String> getChildData(@NotNull NewVirtualFileSystem fs,
                                                                              @NotNull VirtualFile parent,
                                                                              @NotNull String name,
                                                                              @Nullable FileAttributes attributes,
                                                                              @Nullable String symlinkTarget) {
    if (attributes == null) {
      if (".".equals(name) || "..".equals(name)) {
        //these names have special meaning, so FS will report that such children exist, but they must not be added to VFS
        return null;
      }
      FakeVirtualFile virtualFile = new FakeVirtualFile(parent, name);
      attributes = fs.getAttributes(virtualFile);
      symlinkTarget = attributes != null && attributes.isSymLink() ? fs.resolveSymLink(virtualFile) : null;
    }
    return attributes == null ? null : new Pair<>(attributes, symlinkTarget);
  }

  private void executeDelete(@NotNull VFileDeleteEvent event) {
    VirtualFile file = event.getFile();
    if (!file.exists()) {
      LOG.error("Deleting a file which does not exist: " +((VirtualFileWithId)file).getId()+ " "+file.getPath());
      return;
    }
    clearIdCache();

    int id = getFileId(file);

    VirtualFile parent = file.getParent();
    int parentId = parent == null ? 0 : getFileId(parent);

    if (parentId == 0) {
      String rootUrl = UriUtil.trimTrailingSlashes(file.getUrl());
      synchronized (myRoots) {
        myRoots.remove(rootUrl);
        myIdToDirCache.remove(id);
        FSRecords.deleteRootRecord(id);
      }
    }
    else {
      removeIdFromChildren(parent, parentId, id);
      VirtualDirectoryImpl directory = (VirtualDirectoryImpl)file.getParent();
      assert directory != null : file;
      directory.removeChild(file);
    }

    FSRecords.deleteRecordRecursively(id);

    invalidateSubtree(file, "File deleted", event);
    incStructuralModificationCount();
  }

  private static void invalidateSubtree(@NotNull VirtualFile file, @NotNull Object source, @NotNull Object reason) {
    VirtualFileSystemEntry root = (VirtualFileSystemEntry)file;
    if (root.isDirectory()) {
      Queue<VirtualFile> queue = new ArrayDeque<>(root.getCachedChildren());
      while (!queue.isEmpty()) {
        VirtualFileSystemEntry child = (VirtualFileSystemEntry)queue.remove();
        queue.addAll(child.getCachedChildren());
        doInvalidate(child, source, reason);
      }
    }
    doInvalidate(root, source, reason);
  }

  private static void doInvalidate(@NotNull VirtualFileSystemEntry file, @NotNull Object source, @NotNull Object reason) {
    if (file.is(VFileProperty.SYMLINK)) {
      VirtualFileSystem fs = file.getFileSystem();
      if (fs instanceof LocalFileSystemImpl) {
        ((LocalFileSystemImpl)fs).symlinkRemoved(file.getId());
      }
    }
    file.invalidate(source, reason);
  }

  private static void removeIdFromChildren(@NotNull VirtualFile parent, int parentId, int childId) {
    ChildInfo toRemove = new ChildInfoImpl(childId, ChildInfoImpl.UNKNOWN_ID_YET, null, null, null);
    FSRecords.update(parent, parentId, list -> list.remove(toRemove));
  }

  private static void executeRename(@NotNull VirtualFile file, @NotNull String newName) {
    int id = getFileId(file);
    FSRecords.setName(id, newName, ((VirtualFileSystemEntry)file).getNameId());
    ((VirtualFileSystemEntry)file).setNewName(newName);
  }

  private static void executeSetWritable(@NotNull VirtualFile file, boolean writableFlag) {
    setFlag(file, Flags.IS_READ_ONLY, !writableFlag);
    ((VirtualFileSystemEntry)file).setWritableFlag(writableFlag);
  }

  private static void executeSetHidden(@NotNull VirtualFile file, boolean hiddenFlag) {
    setFlag(file, Flags.IS_HIDDEN, hiddenFlag);
    ((VirtualFileSystemEntry)file).setHiddenFlag(hiddenFlag);
  }

  @ApiStatus.Experimental
  public static void setOfflineByDefault(@NotNull VirtualFile file, boolean offlineByDefaultFlag) {
    setFlag(file, Flags.OFFLINE_BY_DEFAULT, offlineByDefaultFlag);
    if (offlineByDefaultFlag) {
      ((VirtualFileSystemEntry)file).setOffline(true);
    }
  }

  private static void executeSetTarget(@NotNull VirtualFile file, @Nullable String target) {
    int id = getFileId(file);
    FSRecords.storeSymlinkTarget(id, target);
    VirtualFileSystem fs = file.getFileSystem();
    if (fs instanceof LocalFileSystemImpl) {
      ((LocalFileSystemImpl)fs).symlinkUpdated(id, file.getParent(), file.getNameSequence(), file.getPath(), target);
    }
  }

  private static void setFlag(@NotNull VirtualFile file, @PersistentFS.Attributes int mask, boolean value) {
    setFlag(getFileId(file), mask, value);
  }

  private static void setFlag(int id, @PersistentFS.Attributes int mask, boolean value) {
    int oldFlags = FSRecords.getFlags(id);
    int flags = value ? oldFlags | mask : oldFlags & ~mask;

    if (oldFlags != flags) {
      FSRecords.setFlags(id, flags);
    }
  }

  private static void executeTouch(@NotNull VirtualFile file,
                                   boolean reloadContentFromFS,
                                   long newModificationStamp,
                                   long newLength,
                                   long newTimestamp) {
    if (reloadContentFromFS) {
      setFlag(file, Flags.MUST_RELOAD_CONTENT, true);
    }

    int fileId = getFileId(file);
    setLength(fileId, newLength);
    FSRecords.setTimestamp(fileId, newTimestamp);

    ((VirtualFileSystemEntry)file).setModificationStamp(newModificationStamp);
  }

  private void executeMove(@NotNull VirtualFile file, @NotNull VirtualFile newParent) {
    clearIdCache();

    int fileId = getFileId(file);
    int newParentId = getFileId(newParent);
    VirtualFile oldParent = file.getParent();
    int oldParentId = getFileId(oldParent);

    VirtualFileSystemEntry virtualFileSystemEntry = (VirtualFileSystemEntry)file;

    removeIdFromChildren(oldParent, oldParentId, fileId);
    FSRecords.setParent(fileId, newParentId);
    ChildInfo newChild = new ChildInfoImpl(fileId, virtualFileSystemEntry.getNameId(), null, null, null);
    FSRecords.update(newParent, newParentId, children -> {
      // check that names are not duplicated
      ChildInfo duplicate = findExistingChildInfo(file, file.getName(), children.children);
      if (duplicate != null) return children;
      return children.insert(newChild);
    });
    virtualFileSystemEntry.setParent(newParent);
  }

  @Override
  public @NotNull String getName(int id) {
    assert id > 0;
    return FSRecords.getName(id);
  }

  @TestOnly
  public static void cleanPersistedContent(int id) {
    doCleanPersistedContent(id);
  }

  @TestOnly
  public void cleanPersistedContents() {
    IntArrayList ids = new IntArrayList(FSRecords.listRoots());
    while (!ids.isEmpty()) {
      int id = ids.popInt();
      if (isDirectory(getFileAttributes(id))) {
        int[] children = FSRecords.listIds(id);
        ids.addElements(ids.size(), children, 0, children.length);
      }
      else {
        doCleanPersistedContent(id);
      }
    }
  }

  private static void doCleanPersistedContent(int id) {
    setFlag(id, Flags.MUST_RELOAD_CONTENT, true);
    setFlag(id, Flags.MUST_RELOAD_LENGTH, true);
  }

  @Override
  public boolean mayHaveChildren(int id) {
    return FSRecords.mayHaveChildren(id);
  }

  @Override
  public @NotNull VfsLog getVfsLog() {
    return myVfsLog;
  }

  @TestOnly
  @NotNull Iterable<? extends VirtualFileSystemEntry> getDirCache() {
    return myIdToDirCache.getCachedDirs();
  }

  static @Attributes int fileAttributesToFlags(@NotNull FileAttributes attributes) {
    FileAttributes.CaseSensitivity sensitivity = attributes.areChildrenCaseSensitive();
    boolean isCaseSensitive = sensitivity == FileAttributes.CaseSensitivity.SENSITIVE;
    return fileAttributesToFlags(attributes.isDirectory(), attributes.isWritable(), attributes.isSymLink(), attributes.isSpecial(),
                                 attributes.isHidden(), sensitivity != FileAttributes.CaseSensitivity.UNKNOWN, isCaseSensitive);
  }

  public static @Attributes int fileAttributesToFlags(boolean isDirectory,
                                                      boolean isWritable,
                                                      boolean isSymLink,
                                                      boolean isSpecial,
                                                      boolean isHidden,
                                                      boolean isChildrenCaseSensitivityCached,
                                                      boolean areChildrenCaseSensitive) {
    return (isDirectory ? Flags.IS_DIRECTORY : 0) |
           (isWritable ? 0 : Flags.IS_READ_ONLY) |
           (isSymLink ? Flags.IS_SYMLINK : 0) |
           (isSpecial ? Flags.IS_SPECIAL : 0) |
           (isHidden ? Flags.IS_HIDDEN : 0) |
           (isChildrenCaseSensitivityCached ? Flags.CHILDREN_CASE_SENSITIVITY_CACHED : 0) |
           (areChildrenCaseSensitive ? Flags.CHILDREN_CASE_SENSITIVE : 0);
  }

  private static final HashingStrategy<VFileCreateEvent> CASE_INSENSITIVE_STRATEGY = new HashingStrategy<>() {
    @Override
    public int hashCode(@Nullable VFileCreateEvent object) {
      return object == null ? 0 : Strings.stringHashCodeInsensitive(object.getChildName());
    }

    @Override
    public boolean equals(VFileCreateEvent o1, VFileCreateEvent o2) {
      if (o1 == o2) {
        return true;
      }
      return o2 != null && o1.getChildName().equalsIgnoreCase(o2.getChildName());
    }
  };
}
