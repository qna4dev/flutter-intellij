/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.inspector;

import com.google.common.base.Joiner;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.impl.XSourcePositionImpl;
import com.jetbrains.lang.dart.psi.DartCallExpression;
import com.jetbrains.lang.dart.psi.DartExpression;
import com.jetbrains.lang.dart.psi.DartReferenceExpression;
import io.flutter.bazel.Workspace;
import io.flutter.bazel.WorkspaceCache;
import io.flutter.pub.PubRoot;
import io.flutter.run.FlutterDebugProcess;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.utils.JsonUtils;
import io.flutter.utils.StreamSubscription;
import io.flutter.utils.VmServiceListenerAdapter;
import io.flutter.vmService.ServiceExtensions;
import io.flutter.vmService.VmServiceConsumers;
import io.flutter.vmService.frame.DartVmServiceValue;
import org.dartlang.analysis.server.protocol.FlutterOutline;
import org.dartlang.vm.service.VmService;
import org.dartlang.vm.service.consumer.ServiceExtensionConsumer;
import org.dartlang.vm.service.element.Event;
import org.dartlang.vm.service.element.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Manages all communication between inspector code running on the DartVM and inspector code running in the IDE.
 */
public class InspectorService implements Disposable {

  public static class Location {

    public Location(@NotNull VirtualFile file, int line, int column, int offset) {
      this.file = file;
      this.line = line;
      this.column = column;
      this.offset = offset;
    }

    @NotNull private final VirtualFile file;
    private final int line;
    private final int column;
    private final int offset;

    public int getLine() {
      return line;
    }

    public int getColumn() {
      return column;
    }

    public int getOffset() {
      return offset;
    }

    @NotNull
    public VirtualFile getFile() {
      return file;
    }

    @NotNull
    public String getPath() {
      return toSourceLocationUri(file.getPath());
    }

    @Nullable
    public XSourcePosition getXSourcePosition() {
      final int line = getLine();
      final int column = getColumn();
      if (line < 0 || column < 0) {
        return null;
      }
      return XSourcePositionImpl.create(file, line - 1, column - 1);
    }

    public static InspectorService.Location outlineToLocation(Project project,
                                                              VirtualFile file,
                                                              FlutterOutline outline,
                                                              Document document) {
      if (file == null) return null;
      if (document == null) return null;
      if (outline == null || outline.getClassName() == null) return null;
      final int documentLength = document.getTextLength();
      int nodeOffset = Math.max(0, Math.min(outline.getCodeOffset(), documentLength));
      final int nodeEndOffset = Math.max(0, Math.min(outline.getCodeOffset() + outline.getCodeLength(), documentLength));

      // The DartOutline will give us the offset of
      // 'child: Foo.bar(...)'
      // but we need the offset of 'bar(...)' for consistentency with the
      // Flutter kernel transformer.
      if (outline.getClassName() != null) {
        final PsiFile psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (psiFile != null) {
          final PsiElement element = psiFile.findElementAt(nodeOffset);
          final DartCallExpression callExpression = PsiTreeUtil.getParentOfType(element, DartCallExpression.class);
          PsiElement match = null;
          if (callExpression != null) {
            final DartExpression expression = callExpression.getExpression();
            if (expression instanceof DartReferenceExpression) {
              final DartReferenceExpression referenceExpression = (DartReferenceExpression)expression;
              final PsiElement[] children = referenceExpression.getChildren();
              if (children.length > 1) {
                // This case handles expressions like 'ClassName.namedConstructor'
                // and 'libraryPrefix.ClassName.namedConstructor'
                match = children[children.length - 1];
              }
              else {
                // this case handles the simple 'ClassName' case.
                match = referenceExpression;
              }
            }
          }
          if (match != null) {
            nodeOffset = match.getTextOffset();
          }
        }
      }
      final int line = document.getLineNumber(nodeOffset);
      final int lineStartOffset = document.getLineStartOffset(line);
      final int column = nodeOffset - lineStartOffset;
      return new InspectorService.Location(file, line + 1, column + 1, nodeOffset);
    }

    /**
     * Returns a location for a FlutterOutline object that makes a best effort
     * to be compatible with the locations generated by the flutter kernel
     * transformer to track creation locations.
     */
    @Nullable
    public static InspectorService.Location outlineToLocation(Editor editor, FlutterOutline outline) {
      if (!(editor instanceof EditorEx)) return null;
      final EditorEx editorEx = (EditorEx)editor;
      return outlineToLocation(editor.getProject(), editorEx.getVirtualFile(), outline, editor.getDocument());
    }
  }

  private static int nextGroupId = 0;

  public static class InteractiveScreenshot {
    InteractiveScreenshot(Screenshot screenshot, ArrayList<DiagnosticsNode> boxes, ArrayList<DiagnosticsNode> elements) {
      this.screenshot = screenshot;
      this.boxes = boxes;
      this.elements = elements;
    }

    public final Screenshot screenshot;
    public final ArrayList<DiagnosticsNode> boxes;
    public final ArrayList<DiagnosticsNode> elements;
  }

  @NotNull private final FlutterApp app;
  @NotNull private final FlutterDebugProcess debugProcess;
  @NotNull private final VmService vmService;
  @NotNull private final Set<InspectorServiceClient> clients;
  @NotNull private final EvalOnDartLibrary inspectorLibrary;
  @NotNull private final Set<String> supportedServiceMethods;

  private final StreamSubscription<Boolean> setPubRootDirectoriesSubscription;

  /**
   * Convenience ObjectGroup constructor for users who need to use DiagnosticsNode objects before the InspectorService is available.
   */
  public static CompletableFuture<InspectorService.ObjectGroup> createGroup(
    @NotNull FlutterApp app, @NotNull FlutterDebugProcess debugProcess,
    @NotNull VmService vmService, String groupName) {
    return create(app, debugProcess, vmService).thenApplyAsync((service) -> service.createObjectGroup(groupName));
  }

  public static CompletableFuture<InspectorService> create(@NotNull FlutterApp app,
                                                           @NotNull FlutterDebugProcess debugProcess,
                                                           @NotNull VmService vmService) {
    assert app.getVMServiceManager() != null;
    final Set<String> inspectorLibraryNames = new HashSet<>();
    inspectorLibraryNames.add("package:flutter/src/widgets/widget_inspector.dart");
    final EvalOnDartLibrary inspectorLibrary = new EvalOnDartLibrary(
      inspectorLibraryNames,
      vmService,
      app.getVMServiceManager()
    );
    final CompletableFuture<Library> libraryFuture =
      inspectorLibrary.libraryRef.thenComposeAsync((library) -> inspectorLibrary.getLibrary(library, null));
    return libraryFuture.thenComposeAsync((Library library) -> {
      for (ClassRef classRef : library.getClasses()) {
        if ("WidgetInspectorService".equals(classRef.getName())) {
          return inspectorLibrary.getClass(classRef, null).thenApplyAsync((ClassObj classObj) -> {
            final Set<String> functionNames = new HashSet<>();
            for (FuncRef funcRef : classObj.getFunctions()) {
              functionNames.add(funcRef.getName());
            }
            return functionNames;
          });
        }
      }
      throw new RuntimeException("WidgetInspectorService class not found");
    }).thenApplyAsync(
      (supportedServiceMethods) -> new InspectorService(
        app, debugProcess, vmService, inspectorLibrary, supportedServiceMethods));
  }

  private InspectorService(@NotNull FlutterApp app,
                           @NotNull FlutterDebugProcess debugProcess,
                           @NotNull VmService vmService,
                           @NotNull EvalOnDartLibrary inspectorLibrary,
                           @NotNull Set<String> supportedServiceMethods) {
    this.vmService = vmService;
    this.app = app;
    this.debugProcess = debugProcess;
    this.inspectorLibrary = inspectorLibrary;
    this.supportedServiceMethods = supportedServiceMethods;

    clients = new HashSet<>();

    vmService.addVmServiceListener(new VmServiceListenerAdapter() {
      @Override
      public void received(String streamId, Event event) {
        onVmServiceReceived(streamId, event);
      }

      @Override
      public void connectionClosed() {
        // TODO(jacobr): dispose?
      }
    });

    vmService.streamListen(VmService.EXTENSION_STREAM_ID, VmServiceConsumers.EMPTY_SUCCESS_CONSUMER);

    assert (app.getVMServiceManager() != null);
    setPubRootDirectoriesSubscription =
      app.getVMServiceManager().hasServiceExtension(ServiceExtensions.setPubRootDirectories, (Boolean available) -> {
        if (!available) {
          return;
        }
        final Workspace workspace = WorkspaceCache.getInstance(app.getProject()).get();
        final ArrayList<String> rootDirectories = new ArrayList<>();
        if (workspace != null) {
          for (VirtualFile root : rootsForProject(app.getProject())) {
            final String relativePath = workspace.getRelativePath(root);
            // TODO(jacobr): is it an error that the relative path can sometimes be null?
            if (relativePath != null) {
              rootDirectories.add(Workspace.BAZEL_URI_SCHEME + "/" + relativePath);
            }
          }
        }
        else {
          for (PubRoot root : app.getPubRoots()) {
            String path = root.getRoot().getCanonicalPath();
            if (SystemInfo.isWindows) {
              // TODO(jacobr): remove after https://github.com/flutter/flutter-intellij/issues/2217.
              // The problem is setPubRootDirectories is currently expecting
              // valid URIs as opposed to windows paths.
              path = "file:///" + path;
            }
            rootDirectories.add(path);
          }
        }
        setPubRootDirectories(rootDirectories);
      });
  }

  @NotNull
  private static List<VirtualFile> rootsForProject(@NotNull Project project) {
    final List<VirtualFile> result = new ArrayList<>();
    for (Module module : ModuleManager.getInstance(project).getModules()) {
      Collections.addAll(result, ModuleRootManager.getInstance(module).getContentRoots());
    }
    return result;
  }

  /**
   * Returns whether to use the VM service extension API or use eval to invoke
   * the protocol directly.
   * <p>
   * Eval must be used when paused at a breakpoint as the VM Service extensions
   * API calls won't execute until after the current frame is done rendering.
   * TODO(jacobr): evaluate whether we should really be trying to execute while
   * a frame is rendering at all as the Element tree may be in a broken state.
   */
  private boolean useServiceExtensionApi() {
    return !app.isFlutterIsolateSuspended();
  }

  public boolean isDetailsSummaryViewSupported() {
    return hasServiceMethod("getSelectedSummaryWidget");
  }

  public boolean isHotUiScreenMirrorSupported() {
    // Somewhat arbitrarily chosen new API that is required for full Hot UI
    // support.
    return hasServiceMethod("getBoundingBoxes");
  }

  /**
   * Use this method to write code that is backwards compatible with versions
   * of Flutter that are too old to contain specific service methods.
   */
  private boolean hasServiceMethod(String methodName) {
    return supportedServiceMethods.contains(methodName);
  }

  @NotNull
  public FlutterDebugProcess getDebugProcess() {
    return debugProcess;
  }

  public FlutterApp getApp() {
    return debugProcess.getApp();
  }

  public ObjectGroup createObjectGroup(String debugName) {
    return new ObjectGroup(this, debugName);
  }

  @NotNull
  private EvalOnDartLibrary getInspectorLibrary() {
    return inspectorLibrary;
  }

  @Override
  public void dispose() {
    Disposer.dispose(inspectorLibrary);
    Disposer.dispose(setPubRootDirectoriesSubscription);
  }

  public CompletableFuture<?> forceRefresh() {
    final List<CompletableFuture<?>> futures = new ArrayList<>();

    for (InspectorServiceClient client : clients) {
      final CompletableFuture<?> future = client.onForceRefresh();
      if (future != null && !future.isDone()) {
        futures.add(future);
      }
    }

    if (futures.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
  }

  private void notifySelectionChanged(boolean uiAlreadyUpdated, boolean textEditorUpdated) {
    ApplicationManager.getApplication().invokeLater(() -> {
      for (InspectorServiceClient client : clients) {
        client.onInspectorSelectionChanged(uiAlreadyUpdated, textEditorUpdated);
      }
    });
  }

  public void addClient(InspectorServiceClient client) {
    clients.add(client);
  }

  public void removeClient(InspectorServiceClient client) {
    clients.remove(client);
  }

  private void onVmServiceReceived(String streamId, Event event) {
    switch (streamId) {
      case VmService.DEBUG_STREAM_ID: {
        if (event.getKind() == EventKind.Inspect) {
          // Assume the inspector in Flutter DevTools or on the device widget
          // inspector has already set the selection on the device so we don't
          // have to. Having multiple clients set the selection risks race
          // conditions where the selection ping-pongs back and forth.

          // Update the UI in IntelliJ.
          notifySelectionChanged(false, false);
        }
        break;
      }
      case VmService.EXTENSION_STREAM_ID: {
        if ("Flutter.Frame".equals(event.getExtensionKind())) {
          ApplicationManager.getApplication().invokeLater(() -> {
            for (InspectorServiceClient client : clients) {
              client.onFlutterFrame();
            }
          });
        }
        break;
      }
      case "ToolEvent": {
        Optional<Event> eventOrNull = Optional.ofNullable(event);
        if ("navigate".equals(eventOrNull.map(Event::getExtensionKind).orElse(null))) {
          JsonObject json = eventOrNull.map(Event::getExtensionData).map(ExtensionData::getJson).orElse(null);
          if (json == null) return;

          String fileUri = JsonUtils.getStringMember(json, "fileUri");
          if (fileUri == null) return;

          String path;
          try {
            path = new URL(fileUri).getFile();
          }
          catch (MalformedURLException e) {
            return;
          }
          if (path == null) return;

          VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
          final int line = JsonUtils.getIntMember(json, "line");
          final int column = JsonUtils.getIntMember(json, "column");;

          ApplicationManager.getApplication().invokeLater(() -> {
            if (file != null && line >= 0 && column >= 0) {
              XSourcePositionImpl position = XSourcePositionImpl.create(file, line - 1, column - 1);
              position.createNavigatable(app.getProject()).navigate(false);
            }
          });
        }
        break;
      }
      default:
    }
  }

  /**
   * If the widget tree is not ready, the application should wait for the next
   * Flutter.Frame event before attempting to display the widget tree. If the
   * application is ready, the next Flutter.Frame event may never come as no
   * new frames will be triggered to draw unless something changes in the UI.
   */
  public CompletableFuture<Boolean> isWidgetTreeReady() {
    if (useServiceExtensionApi()) {
      return invokeServiceExtensionNoGroup("isWidgetTreeReady", new JsonObject())
        .thenApplyAsync(JsonElement::getAsBoolean);
    }
    else {
      return invokeEvalNoGroup("isWidgetTreeReady")
        .thenApplyAsync((InstanceRef ref) -> "true".equals(ref.getValueAsString()));
    }
  }

  CompletableFuture<JsonElement> invokeServiceExtensionNoGroup(String methodName, List<String> args) {
    final JsonObject params = new JsonObject();
    for (int i = 0; i < args.size(); ++i) {
      params.addProperty("arg" + i, args.get(i));
    }
    return invokeServiceExtensionNoGroup(methodName, params);
  }

  private CompletableFuture<Void> setPubRootDirectories(List<String> rootDirectories) {
    if (useServiceExtensionApi()) {
      return invokeServiceExtensionNoGroup("setPubRootDirectories", rootDirectories).thenApplyAsync((ignored) -> null);
    }
    else {
      // TODO(jacobr): remove this call as soon as
      // `ext.flutter.inspector.*` has been in two revs of the Flutter Beta
      // channel. The feature landed in the Flutter dev chanel on
      // April 16, 2018.
      final JsonArray jsonArray = new JsonArray();
      for (String rootDirectory : rootDirectories) {
        jsonArray.add(rootDirectory);
      }
      return getInspectorLibrary().eval(
        "WidgetInspectorService.instance.setPubRootDirectories(" + new Gson().toJson(jsonArray) + ")", null, null)
        .thenApplyAsync((instance) -> null);
    }
  }

  CompletableFuture<InstanceRef> invokeEvalNoGroup(String methodName) {
    return getInspectorLibrary().eval("WidgetInspectorService.instance." + methodName + "()", null, null);
  }

  CompletableFuture<JsonElement> invokeServiceExtensionNoGroup(String methodName, JsonObject params) {
    return invokeServiceExtensionHelper(methodName, params);
  }

  private CompletableFuture<JsonElement> invokeServiceExtensionHelper(String methodName, JsonObject params) {
    // Workaround null values turning into the string "null" when using the VM Service extension protocol.
    final ArrayList<String> keysToRemove = new ArrayList<>();

    for (String key : JsonUtils.getKeySet(params)) {
      if (params.get(key).isJsonNull()) {
        keysToRemove.add(key);
      }
    }
    for (String key : keysToRemove) {
      params.remove(key);
    }
    final CompletableFuture<JsonElement> ret = new CompletableFuture<>();
    vmService.callServiceExtension(
      getInspectorLibrary().getIsolateId(), ServiceExtensions.inspectorPrefix + methodName, params,
      new ServiceExtensionConsumer() {
        @Override
        public void received(JsonObject object) {
          if (object == null) {
            ret.complete(null);
          }
          else {
            ret.complete(object.get("result"));
          }
        }

        @Override
        public void onError(RPCError error) {
          ret.completeExceptionally(new RuntimeException("RPCError calling " + methodName + ": " + error.getMessage()));
        }
      }
    );
    return ret;
  }

  /**
   * Class managing a group of inspector objects that can be freed by
   * a single call to dispose().
   * After dispose is called, all pending requests made with the ObjectGroup
   * will be skipped. This means that clients should not have to write any
   * special logic to handle orphaned requests.
   * <p>
   * safeWhenComplete is the recommended way to await futures returned by the
   * ObjectGroup as with that method the callback will be skipped if the
   * ObjectGroup is disposed making it easy to get the correct behavior of
   * skipping orphaned requests. Otherwise, code needs to handle getting back
   * futures that return null values for requests from disposed ObjectGroup
   * objects.
   */
  @SuppressWarnings("CodeBlock2Expr")
  public class ObjectGroup implements Disposable {
    final InspectorService service;
    /**
     * Object group all objects in this arena are allocated with.
     */
    final String groupName;

    volatile boolean disposed;
    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private ObjectGroup(InspectorService service, String debugName) {
      this.service = service;
      this.groupName = debugName + "_" + nextGroupId;
      nextGroupId++;
    }

    public InspectorService getInspectorService() {
      return service;
    }

    /**
     * Once an ObjectGroup has been disposed, all methods returning
     * DiagnosticsNode objects will return a placeholder dummy node and all methods
     * returning lists or maps will return empty lists and all other methods will
     * return null. Generally code should never call methods on a disposed object
     * group but sometimes due to chained futures that can be difficult to avoid
     * and it is simpler return an empty result that will be ignored anyway than to
     * attempt carefully cancel futures.
     */
    @Override
    public void dispose() {
      if (disposed) {
        return;
      }
      lock.writeLock().lock();
      invokeVoidServiceMethod("disposeGroup", groupName);
      disposed = true;
      lock.writeLock().unlock();
    }

    private <T> CompletableFuture<T> nullIfDisposed(Supplier<CompletableFuture<T>> supplier) {
      lock.readLock().lock();
      if (disposed) {
        lock.readLock().unlock();
        return CompletableFuture.completedFuture(null);
      }

      try {
        return supplier.get();
      }
      finally {
        lock.readLock().unlock();
      }
    }

    private <T> T nullValueIfDisposed(Supplier<T> supplier) {
      lock.readLock().lock();
      if (disposed) {
        lock.readLock().unlock();
        return null;
      }

      try {
        return supplier.get();
      }
      finally {
        lock.readLock().unlock();
      }
    }

    private void skipIfDisposed(Runnable runnable) {
      lock.readLock().lock();
      if (disposed) {
        return;
      }

      try {
        runnable.run();
      }
      finally {
        lock.readLock().unlock();
      }
    }

    public CompletableFuture<XSourcePosition> getPropertyLocation(InstanceRef instanceRef, String name) {
      return nullIfDisposed(() -> getInstance(instanceRef)
        .thenComposeAsync((Instance instance) -> nullValueIfDisposed(() -> getPropertyLocationHelper(instance.getClassRef(), name))));
    }

    public CompletableFuture<XSourcePosition> getPropertyLocationHelper(ClassRef classRef, String name) {
      return nullIfDisposed(() -> inspectorLibrary.getClass(classRef, this).thenComposeAsync((ClassObj clazz) -> {
        return nullIfDisposed(() -> {
          for (FuncRef f : clazz.getFunctions()) {
            // TODO(pq): check for private properties that match name.
            if (f.getName().equals(name)) {
              return inspectorLibrary.getFunc(f, this).thenComposeAsync((Func func) -> nullIfDisposed(() -> {
                final SourceLocation location = func.getLocation();
                return inspectorLibrary.getSourcePosition(debugProcess, location.getScript(), location.getTokenPos(), this);
              }));
            }
          }
          final ClassRef superClass = clazz.getSuperClass();
          return superClass == null ? CompletableFuture.completedFuture(null) : getPropertyLocationHelper(superClass, name);
        });
      }));
    }

    public CompletableFuture<DiagnosticsNode> getRoot(FlutterTreeType type) {
      // There is no excuse to call this method on a disposed group.
      assert (!disposed);
      switch (type) {
        case widget:
          return getRootWidget();
        case renderObject:
          return getRootRenderObject();
      }
      throw new RuntimeException("Unexpected FlutterTreeType");
    }

    /**
     * Invokes a static method on the WidgetInspectorService class passing in the specified
     * arguments.
     * <p>
     * Intent is we could refactor how the API is invoked by only changing this call.
     */
    CompletableFuture<InstanceRef> invokeEval(String methodName) {
      return nullIfDisposed(() -> invokeEval(methodName, groupName));
    }

    CompletableFuture<InstanceRef> invokeEval(String methodName, String arg1) {
      return nullIfDisposed(
        () -> getInspectorLibrary().eval("WidgetInspectorService.instance." + methodName + "(\"" + arg1 + "\")", null, this));
    }

    CompletableFuture<JsonElement> invokeVmServiceExtension(String methodName) {
      return invokeVmServiceExtension(methodName, groupName);
    }

    CompletableFuture<JsonElement> invokeVmServiceExtension(String methodName, String objectGroup) {
      final JsonObject params = new JsonObject();
      params.addProperty("objectGroup", objectGroup);
      return invokeVmServiceExtension(methodName, params);
    }

    CompletableFuture<JsonElement> invokeVmServiceExtension(String methodName, String arg, String objectGroup) {
      final JsonObject params = new JsonObject();
      params.addProperty("arg", arg);
      params.addProperty("objectGroup", objectGroup);
      return invokeVmServiceExtension(methodName, params);
    }

    // All calls to invokeVmServiceExtension bottom out to this call.
    CompletableFuture<JsonElement> invokeVmServiceExtension(String methodName, JsonObject paramsMap) {
      return getInspectorLibrary().addRequest(
        this,
        methodName,
        () -> invokeServiceExtensionHelper(methodName, paramsMap)
      );
    }

    CompletableFuture<JsonElement> invokeVmServiceExtension(String methodName, InspectorInstanceRef arg) {
      if (arg == null || arg.getId() == null) {
        return invokeVmServiceExtension(methodName, null, groupName);
      }
      return invokeVmServiceExtension(methodName, arg.getId(), groupName);
    }

    private void addLocationToParams(Location location, JsonObject params) {
      if (location == null) return;
      params.addProperty("file", location.getPath());
      params.addProperty("line", location.getLine());
      params.addProperty("column", location.getColumn());
    }

    public CompletableFuture<ArrayList<DiagnosticsNode>> getElementsAtLocation(Location location, int count) {
      final JsonObject params = new JsonObject();
      addLocationToParams(location, params);
      params.addProperty("count", count);
      params.addProperty("groupName", groupName);

      return parseDiagnosticsNodesDaemon(
        inspectorLibrary.invokeServiceMethod("ext.flutter.inspector.getElementsAtLocation", params).thenApplyAsync((o) -> {
          if (o == null) return null;
          return o.get("result");
        }), null);
    }

    public CompletableFuture<ArrayList<DiagnosticsNode>> getBoundingBoxes(DiagnosticsNode root, DiagnosticsNode target) {
      final JsonObject params = new JsonObject();
      if (root == null || target == null || root.getValueRef() == null || target.getValueRef() == null) {
        return CompletableFuture.completedFuture(new ArrayList<>());
      }
      params.addProperty("rootId", root.getValueRef().getId());
      params.addProperty("targetId", target.getValueRef().getId());
      params.addProperty("groupName", groupName);

      return parseDiagnosticsNodesDaemon(
        inspectorLibrary.invokeServiceMethod("ext.flutter.inspector.getBoundingBoxes", params).thenApplyAsync((o) -> {
          if (o == null) return null;
          return o.get("result");
        }), null);
    }

    public CompletableFuture<ArrayList<DiagnosticsNode>> hitTest(DiagnosticsNode root,
                                                                 double dx,
                                                                 double dy,
                                                                 String file,
                                                                 int startLine,
                                                                 int endLine) {
      final JsonObject params = new JsonObject();
      if (root == null || root.getValueRef() == null) {
        return CompletableFuture.completedFuture(new ArrayList<>());
      }
      params.addProperty("id", root.getValueRef().getId());
      params.addProperty("dx", dx);
      params.addProperty("dy", dy);
      if (file != null) {
        params.addProperty("file", file);
      }

      if (startLine >= 0 && endLine >= 0) {
        params.addProperty("startLine", startLine);
        params.addProperty("endLine", endLine);
      }

      params.addProperty("groupName", groupName);

      return parseDiagnosticsNodesDaemon(
        inspectorLibrary.invokeServiceMethod("ext.flutter.inspector.hitTest", params).thenApplyAsync((o) -> {
          if (o == null) return null;
          return o.get("result");
        }), null);
    }

    public CompletableFuture<Boolean> setColorProperty(DiagnosticsNode target, Color color) {
      // We implement this method directly here rather than landing it in
      // package:flutter as the right long term solution is to optimize hot reloads of single property changes.

      // This method only supports Container and Text widgets and will intentionally fail for all other cases.

      if (target == null || target.getValueRef() == null || color == null) return CompletableFuture.completedFuture(false);

      final String command =
        "final object = WidgetInspectorService.instance.toObject('" +
        target.getValueRef().getId() +
        "');" +
        "if (object is! Element) return false;\n" +
        "final Element element = object;\n" +
        "final color = Color.fromARGB(" +
        color.getAlpha() +
        "," +
        color.getRed() +
        "," +
        color.getGreen() +
        "," +
        color.getBlue() +
        ");\n" +
        "RenderObject render = element?.renderObject;\n" +
        "\n" +
        "if (render is RenderParagraph) {\n" +
        "  RenderParagraph paragraph = render;\n" +
        "  final InlineSpan inlineSpan = paragraph.text;\n" +
        "  if (inlineSpan is! TextSpan) return false;\n" +
        "  final TextSpan existing = inlineSpan;\n" +
        "  paragraph.text = TextSpan(text: existing.text,\n" +
        "    children: existing.children,\n" +
        "    style: existing.style.copyWith(color: color),\n" +
        "    recognizer: existing.recognizer,\n" +
        "    semanticsLabel: existing.semanticsLabel,\n" +
        "  );\n" +
        "  return true;\n" +
        "} else {\n" +
        "  RenderDecoratedBox findFirstMatching(Element root) {\n" +
        "    RenderDecoratedBox match = null;\n" +
        "    void _matchHelper(Element e) {\n" +
        "      if (match != null || !identical(e, root) && _isLocalCreationLocation(e)) return;\n" +
        "      final r = e.renderObject;\n" +
        "      if (r is RenderDecoratedBox) {\n" +
        "        match = r;\n" +
        "        return;\n" +
        "      }\n" +
        "      e.visitChildElements(_matchHelper);\n" +
        "    }\n" +
        "    _matchHelper(root);\n" +
        "    return match;\n" +
        "  }\n" +
        "\n" +
        "  final RenderDecoratedBox render = findFirstMatching(element);\n" +
        "  if (render != null) {\n" +
        "    final BoxDecoration existingDecoration = render.decoration;\n" +
        "    BoxDecoration decoration;\n" +
        "    if (existingDecoration is BoxDecoration) {\n" +
        "      decoration = existingDecoration.copyWith(color: color);\n" +
        "    } else if (existingDecoration == null) {\n" +
        "      decoration = BoxDecoration(color: color);\n" +
        "    }\n" +
        "    if (decoration != null) {\n" +
        "      render.decoration = decoration;\n" +
        "      return true;\n" +
        "    }\n" +
        "  }\n" +
        "}\n" +
        "return false;\n";

      return evaluateCustomApiHelper(command, new HashMap<>()).thenApplyAsync((instanceRef) -> {
        return instanceRef != null && "true".equals(instanceRef.getValueAsString());
      });
    }

    private CompletableFuture<InstanceRef> evaluateCustomApiHelper(String command, Map<String, String> scope) {
      // Avoid running command if we interrupted executing code as results will
      // be weird. Repeatedly run the command until we hit idle.
      // We cannot execute the command at a later point due to eval bugs where
      // the VM crashes executing a closure created by eval asynchronously.
      if (isDisposed()) return CompletableFuture.completedFuture(null);

      final ArrayList<String> lines = new ArrayList<>();
      lines.add("((){");
      lines.add("if (SchedulerBinding.instance.schedulerPhase != SchedulerPhase.idle) return null;");


      final String[] commandLines = command.split("\n");
      Collections.addAll(lines, commandLines);
      lines.add(")()");

      // Strip out line breaks as that makes the VM evaluate expression api unhappy.
      final String expression = Joiner.on("").join(lines);
      return evalWithRetry(expression, scope);
    }

    private CompletableFuture<InstanceRef> evalWithRetry(String expression, Map<String, String> scope) {
      if (isDisposed()) return CompletableFuture.completedFuture(null);

      return inspectorLibrary.eval(expression, scope, this).thenComposeAsync(
        (instanceRef) -> {
          if (instanceRef == null) {
            // A null value indicates the request was cancelled.
            return CompletableFuture.completedFuture(null);
          }
          if (instanceRef.isNull()) {
            // An InstanceRef with an explicitly null return value indicates we should issue the request again.
            return evalWithRetry(expression, scope);
          }
          return CompletableFuture.completedFuture(instanceRef);
        }
      );
    }

    public CompletableFuture<InteractiveScreenshot> getScreenshotAtLocation(
      Location location,
      int count,
      int width,
      int height,
      double maxPixelRatio) {
      final JsonObject params = new JsonObject();
      addLocationToParams(location, params);
      params.addProperty("count", count);
      params.addProperty("width", width);
      params.addProperty("height", height);
      params.addProperty("maxPixelRatio", maxPixelRatio);
      params.addProperty("groupName", groupName);
      return nullIfDisposed(() -> {
        return inspectorLibrary.invokeServiceMethod("ext.flutter.inspector.screenshotAtLocation", params).thenApplyAsync(
          (JsonObject response) -> {
            if (response == null || response.get("result").isJsonNull()) {
              // No screenshot available.
              return null;
            }
            final JsonObject result = response.getAsJsonObject("result");
            Screenshot screenshot = null;
            final JsonElement screenshotJson = result.get("screenshot");
            if (screenshotJson != null && !screenshotJson.isJsonNull()) {
              screenshot = getScreenshotFromJson(screenshotJson.getAsJsonObject());
            }
            return new InteractiveScreenshot(
              screenshot,
              parseDiagnosticsNodesHelper(result.get("boxes"), null),
              parseDiagnosticsNodesHelper(result.get("elements"), null)
            );
          });
      });
    }

    public CompletableFuture<Screenshot> getScreenshot(InspectorInstanceRef ref, int width, int height, double maxPixelRatio) {
      final JsonObject params = new JsonObject();
      params.addProperty("width", width);
      params.addProperty("height", height);
      params.addProperty("maxPixelRatio", maxPixelRatio);
      params.addProperty("id", ref.getId());

      return nullIfDisposed(
        () -> inspectorLibrary.invokeServiceMethod("ext.flutter.inspector.screenshot", params).thenApplyAsync((JsonObject response) -> {
          if (response == null || response.get("result").isJsonNull()) {
            // No screenshot avaiable.
            return null;
          }
          final JsonObject result = response.getAsJsonObject("result");

          return getScreenshotFromJson(result);
        }));
    }

    @NotNull
    private Screenshot getScreenshotFromJson(JsonObject result) {
      final String imageString = result.getAsJsonPrimitive("image").getAsString();
      // create a buffered image
      final Base64.Decoder decoder = Base64.getDecoder();
      final byte[] imageBytes = decoder.decode(imageString);
      final ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(imageBytes);
      final BufferedImage image;
      try {
        image = ImageIO.read(byteArrayInputStream);
        byteArrayInputStream.close();
      }
      catch (IOException e) {
        throw new RuntimeException("Error decoding image: " + e.getMessage());
      }

      final TransformedRect transformedRect = new TransformedRect(result.getAsJsonObject("transformedRect"));
      return new Screenshot(image, transformedRect);
    }

    CompletableFuture<InstanceRef> invokeEval(String methodName, InspectorInstanceRef arg) {
      return nullIfDisposed(() -> {
        if (arg == null || arg.getId() == null) {
          return getInspectorLibrary().eval("WidgetInspectorService.instance." + methodName + "(null, \"" + groupName + "\")", null, this);
        }
        return getInspectorLibrary()
          .eval("WidgetInspectorService.instance." + methodName + "(\"" + arg.getId() + "\", \"" + groupName + "\")", null, this);
      });
    }

    /**
     * Call a service method passing in an VM Service instance reference.
     * <p>
     * This call is useful when receiving an "inspect" event from the
     * VM Service and future use cases such as inspecting a Widget from the
     * IntelliJ watch window.
     * <p>
     * This method will always need to use the VM Service as the input
     * parameter is an VM Service InstanceRef..
     */
    CompletableFuture<InstanceRef> invokeServiceMethodOnRefEval(String methodName, InstanceRef arg) {
      return nullIfDisposed(() -> {
        final HashMap<String, String> scope = new HashMap<>();
        if (arg == null) {
          return getInspectorLibrary().eval("WidgetInspectorService.instance." + methodName + "(null, \"" + groupName + "\")", scope, this);
        }
        scope.put("arg1", arg.getId());
        return getInspectorLibrary().eval("WidgetInspectorService.instance." + methodName + "(arg1, \"" + groupName + "\")", scope, this);
      });
    }

    CompletableFuture<DiagnosticsNode> parseDiagnosticsNodeVmService(CompletableFuture<InstanceRef> instanceRefFuture) {
      return nullIfDisposed(() -> instanceRefFuture.thenComposeAsync(this::parseDiagnosticsNodeVmService));
    }

    /**
     * Returns a CompletableFuture with a Map of property names to VM Service
     * InstanceRef objects. This method is shorthand for individually evaluating
     * each of the getters specified by property names.
     * <p>
     * It would be nice if the VM Service protocol provided a built in method
     * to get InstanceRef objects for a list of properties but this is
     * sufficient although slightly less efficient. The VM Service protocol
     * does provide fast access to all fields as part of an Instance object
     * but that is inadequate as for many Flutter data objects that we want
     * to display visually we care about properties that are not necessarily
     * fields.
     * <p>
     * The future will immediately complete to null if the inspectorInstanceRef is null.
     */
    public CompletableFuture<Map<String, InstanceRef>> getDartObjectProperties(
      InspectorInstanceRef inspectorInstanceRef, final String[] propertyNames) {
      return nullIfDisposed(
        () -> toVmServiceInstanceRef(inspectorInstanceRef).thenComposeAsync((InstanceRef instanceRef) -> nullIfDisposed(() -> {
          final StringBuilder sb = new StringBuilder();
          final List<String> propertyAccessors = new ArrayList<>();
          final String objectName = "that";
          for (String propertyName : propertyNames) {
            propertyAccessors.add(objectName + "." + propertyName);
          }
          sb.append("[");
          sb.append(Joiner.on(',').join(propertyAccessors));
          sb.append("]");
          final Map<String, String> scope = new HashMap<>();
          scope.put(objectName, instanceRef.getId());
          return getInstance(inspectorLibrary.eval(sb.toString(), scope, this)).thenApplyAsync(
            (Instance instance) -> nullValueIfDisposed(() -> {
              // We now have an instance object that is a Dart array of all the
              // property values. Convert it back to a map from property name to
              // property values.

              final Map<String, InstanceRef> properties = new HashMap<>();
              final ElementList<InstanceRef> values = instance.getElements();
              assert (values.size() == propertyNames.length);
              for (int i = 0; i < propertyNames.length; ++i) {
                properties.put(propertyNames[i], values.get(i));
              }
              return properties;
            }));
        })));
    }

    public CompletableFuture<InstanceRef> toVmServiceInstanceRef(InspectorInstanceRef inspectorInstanceRef) {
      return nullIfDisposed(() -> invokeEval("toObject", inspectorInstanceRef));
    }

    private CompletableFuture<Instance> getInstance(InstanceRef instanceRef) {
      return nullIfDisposed(() -> getInspectorLibrary().getInstance(instanceRef, this));
    }

    CompletableFuture<Instance> getInstance(CompletableFuture<InstanceRef> instanceRefFuture) {
      return nullIfDisposed(() -> instanceRefFuture.thenComposeAsync(this::getInstance));
    }

    CompletableFuture<DiagnosticsNode> parseDiagnosticsNodeVmService(InstanceRef instanceRef) {
      return nullIfDisposed(() -> instanceRefToJson(instanceRef).thenApplyAsync(this::parseDiagnosticsNodeHelper));
    }

    CompletableFuture<DiagnosticsNode> parseDiagnosticsNodeDaemon(CompletableFuture<JsonElement> json) {
      return nullIfDisposed(() -> json.thenApplyAsync(this::parseDiagnosticsNodeHelper));
    }

    DiagnosticsNode parseDiagnosticsNodeHelper(JsonElement jsonElement) {
      return nullValueIfDisposed(() -> {
        if (jsonElement == null || jsonElement.isJsonNull()) {
          return null;
        }
        return new DiagnosticsNode(jsonElement.getAsJsonObject(), this, false, null);
      });
    }

    CompletableFuture<JsonElement> instanceRefToJson(CompletableFuture<InstanceRef> instanceRefFuture) {
      return nullIfDisposed(() -> instanceRefFuture.thenComposeAsync(this::instanceRefToJson));
    }

    /**
     * Requires that the InstanceRef is really referring to a String that is valid JSON.
     */
    CompletableFuture<JsonElement> instanceRefToJson(InstanceRef instanceRef) {
      if (instanceRef.getValueAsString() != null && !instanceRef.getValueAsStringIsTruncated()) {
        // In some situations, the string may already be fully populated.
        final JsonElement json = JsonUtils.parseString(instanceRef.getValueAsString());
        return CompletableFuture.completedFuture(json);
      }
      else {
        // Otherwise, retrieve the full value of the string.
        return nullIfDisposed(() -> getInspectorLibrary().getInstance(instanceRef, this).thenApplyAsync((Instance instance) -> {
          return nullValueIfDisposed(() -> {
            final String json = instance.getValueAsString();
            return JsonUtils.parseString(json);
          });
        }));
      }
    }

    CompletableFuture<ArrayList<DiagnosticsNode>> parseDiagnosticsNodesVmService(InstanceRef instanceRef, DiagnosticsNode parent) {
      return nullIfDisposed(() -> instanceRefToJson(instanceRef).thenApplyAsync((JsonElement jsonElement) -> {
        return nullValueIfDisposed(() -> {
          final JsonArray jsonArray = jsonElement != null ? jsonElement.getAsJsonArray() : null;
          return parseDiagnosticsNodesHelper(jsonArray, parent);
        });
      }));
    }

    ArrayList<DiagnosticsNode> parseDiagnosticsNodesHelper(JsonElement jsonObject, DiagnosticsNode parent) {
      return parseDiagnosticsNodesHelper(jsonObject != null && !jsonObject.isJsonNull() ? jsonObject.getAsJsonArray() : null, parent);
    }

    ArrayList<DiagnosticsNode> parseDiagnosticsNodesHelper(JsonArray jsonArray, DiagnosticsNode parent) {
      return nullValueIfDisposed(() -> {
        if (jsonArray == null) {
          return null;
        }
        final ArrayList<DiagnosticsNode> nodes = new ArrayList<>();
        for (JsonElement element : jsonArray) {
          nodes.add(new DiagnosticsNode(element.getAsJsonObject(), this, false, parent));
        }
        return nodes;
      });
    }

    /**
     * Converts an inspector ref to value suitable for use by generic intellij
     * debugging tools.
     * <p>
     * Warning: FlutterVmServiceValue references do not make any lifetime guarantees
     * so code keeping them around for a long period of time must be prepared to
     * handle reference expiration gracefully.
     */
    public CompletableFuture<DartVmServiceValue> toDartVmServiceValue(InspectorInstanceRef inspectorInstanceRef) {
      return invokeEval("toObject", inspectorInstanceRef).thenApplyAsync(
        (InstanceRef instanceRef) -> nullValueIfDisposed(() -> {
          //noinspection CodeBlock2Expr
          return new DartVmServiceValue(debugProcess, inspectorLibrary.getIsolateId(), "inspectedObject", instanceRef, null, null, false);
        }));
    }

    /**
     * Converts an inspector ref to value suitable for use by generic intellij
     * debugging tools.
     * <p>
     * Warning: FlutterVmServiceValue references do not make any lifetime guarantees
     * so code keeping them around for a long period of time must be prepared to
     * handle reference expiration gracefully.
     */
    public CompletableFuture<DartVmServiceValue> toDartVmServiceValueForSourceLocation(InspectorInstanceRef inspectorInstanceRef) {
      return invokeEval("toObjectForSourceLocation", inspectorInstanceRef).thenApplyAsync(
        (InstanceRef instanceRef) -> nullValueIfDisposed(() -> {
          //noinspection CodeBlock2Expr
          return new DartVmServiceValue(debugProcess, inspectorLibrary.getIsolateId(), "inspectedObject", instanceRef, null, null, false);
        }));
    }

    CompletableFuture<ArrayList<DiagnosticsNode>> parseDiagnosticsNodesVmService(CompletableFuture<InstanceRef> instanceRefFuture,
                                                                                 DiagnosticsNode parent) {
      return nullIfDisposed(
        () -> instanceRefFuture.thenComposeAsync((instanceRef) -> parseDiagnosticsNodesVmService(instanceRef, parent)));
    }

    CompletableFuture<ArrayList<DiagnosticsNode>> parseDiagnosticsNodesDaemon(CompletableFuture<JsonElement> jsonFuture,
                                                                              DiagnosticsNode parent) {
      return nullIfDisposed(() -> jsonFuture.thenApplyAsync((json) -> parseDiagnosticsNodesHelper(json, parent)));
    }

    CompletableFuture<ArrayList<DiagnosticsNode>> getChildren(InspectorInstanceRef instanceRef,
                                                              boolean summaryTree,
                                                              DiagnosticsNode parent) {
      if (isDetailsSummaryViewSupported()) {
        return getListHelper(instanceRef, summaryTree ? "getChildrenSummaryTree" : "getChildrenDetailsSubtree", parent);
      }
      else {
        return getListHelper(instanceRef, "getChildren", parent);
      }
    }

    CompletableFuture<ArrayList<DiagnosticsNode>> getProperties(InspectorInstanceRef instanceRef) {
      return getListHelper(instanceRef, "getProperties", null);
    }

    private CompletableFuture<ArrayList<DiagnosticsNode>> getListHelper(
      InspectorInstanceRef instanceRef, String methodName, DiagnosticsNode parent) {
      return nullIfDisposed(() -> {
        if (useServiceExtensionApi()) {
          return parseDiagnosticsNodesDaemon(invokeVmServiceExtension(methodName, instanceRef), parent);
        }
        else {
          return parseDiagnosticsNodesVmService(invokeEval(methodName, instanceRef), parent);
        }
      });
    }

    public CompletableFuture<DiagnosticsNode> invokeServiceMethodReturningNode(String methodName) {
      return nullIfDisposed(() -> {
        if (useServiceExtensionApi()) {
          return parseDiagnosticsNodeDaemon(invokeVmServiceExtension(methodName));
        }
        else {
          return parseDiagnosticsNodeVmService(invokeEval(methodName));
        }
      });
    }

    public CompletableFuture<DiagnosticsNode> invokeServiceMethodReturningNode(String methodName, InspectorInstanceRef ref) {
      return nullIfDisposed(() -> {
        if (useServiceExtensionApi()) {
          return parseDiagnosticsNodeDaemon(invokeVmServiceExtension(methodName, ref));
        }
        else {
          return parseDiagnosticsNodeVmService(invokeEval(methodName, ref));
        }
      });
    }

    public CompletableFuture<Void> invokeVoidServiceMethod(String methodName, String arg1) {
      return nullIfDisposed(() -> {
        if (useServiceExtensionApi()) {
          return invokeVmServiceExtension(methodName, arg1).thenApply((ignored) -> null);
        }
        else {
          return invokeEval(methodName, arg1).thenApply((ignored) -> null);
        }
      });
    }

    public CompletableFuture<Void> invokeVoidServiceMethod(String methodName, InspectorInstanceRef ref) {
      return nullIfDisposed(() -> {
        if (useServiceExtensionApi()) {
          return invokeVmServiceExtension(methodName, ref).thenApply((ignored) -> null);
        }
        else {
          return invokeEval(methodName, ref).thenApply((ignored) -> null);
        }
      });
    }

    public CompletableFuture<DiagnosticsNode> getRootWidget() {
      return invokeServiceMethodReturningNode(isDetailsSummaryViewSupported() ? "getRootWidgetSummaryTree" : "getRootWidget");
    }

    public CompletableFuture<DiagnosticsNode> getElementForScreenshot() {
      return invokeServiceMethodReturningNode("getElementForScreenshot");
    }

    public CompletableFuture<DiagnosticsNode> getSummaryTreeWithoutIds() {
      return parseDiagnosticsNodeDaemon(invokeVmServiceExtension("getRootWidgetSummaryTree", new JsonObject()));
    }

    public CompletableFuture<DiagnosticsNode> getRootRenderObject() {
      assert (!disposed);
      return invokeServiceMethodReturningNode("getRootRenderObject");
    }

    public CompletableFuture<ArrayList<DiagnosticsPathNode>> getParentChain(DiagnosticsNode target) {
      return nullIfDisposed(() -> {
        if (useServiceExtensionApi()) {
          return parseDiagnosticsPathDaemon(invokeVmServiceExtension("getParentChain", target.getValueRef()));
        }
        else {
          return parseDiagnosticsPathVmService(invokeEval("getParentChain", target.getValueRef()));
        }
      });
    }

    CompletableFuture<ArrayList<DiagnosticsPathNode>> parseDiagnosticsPathVmService(CompletableFuture<InstanceRef> instanceRefFuture) {
      return nullIfDisposed(() -> instanceRefFuture.thenComposeAsync(this::parseDiagnosticsPathVmService));
    }

    private CompletableFuture<ArrayList<DiagnosticsPathNode>> parseDiagnosticsPathVmService(InstanceRef pathRef) {
      return nullIfDisposed(() -> instanceRefToJson(pathRef).thenApplyAsync(this::parseDiagnosticsPathHelper));
    }

    CompletableFuture<ArrayList<DiagnosticsPathNode>> parseDiagnosticsPathDaemon(CompletableFuture<JsonElement> jsonFuture) {
      return nullIfDisposed(() -> jsonFuture.thenApplyAsync(this::parseDiagnosticsPathHelper));
    }

    private ArrayList<DiagnosticsPathNode> parseDiagnosticsPathHelper(JsonElement jsonElement) {
      return nullValueIfDisposed(() -> {
        final JsonArray jsonArray = jsonElement.getAsJsonArray();
        final ArrayList<DiagnosticsPathNode> pathNodes = new ArrayList<>();
        for (JsonElement element : jsonArray) {
          pathNodes.add(new DiagnosticsPathNode(element.getAsJsonObject(), this));
        }
        return pathNodes;
      });
    }

    public CompletableFuture<DiagnosticsNode> getSelection(DiagnosticsNode previousSelection, FlutterTreeType treeType, boolean localOnly) {
      // There is no reason to allow calling this method on a disposed group.
      assert (!disposed);
      return nullIfDisposed(() -> {
        CompletableFuture<DiagnosticsNode> result = null;
        final InspectorInstanceRef previousSelectionRef = previousSelection != null ? previousSelection.getDartDiagnosticRef() : null;

        switch (treeType) {
          case widget:
            result = invokeServiceMethodReturningNode(localOnly ? "getSelectedSummaryWidget" : "getSelectedWidget", previousSelectionRef);
            break;
          case renderObject:
            result = invokeServiceMethodReturningNode("getSelectedRenderObject", previousSelectionRef);
            break;
        }
        return result.thenApplyAsync((DiagnosticsNode newSelection) -> nullValueIfDisposed(() -> {
          if (newSelection != null && newSelection.getDartDiagnosticRef().equals(previousSelectionRef)) {
            return previousSelection;
          }
          else {
            return newSelection;
          }
        }));
      });
    }

    public void setSelection(InspectorInstanceRef selection, boolean uiAlreadyUpdated, boolean textEditorUpdated) {
      if (disposed) {
        return;
      }
      if (selection == null || selection.getId() == null) {
        return;
      }
      if (useServiceExtensionApi()) {
        handleSetSelectionDaemon(invokeVmServiceExtension("setSelectionById", selection), uiAlreadyUpdated, textEditorUpdated);
      }
      else {
        handleSetSelectionVmService(invokeEval("setSelectionById", selection), uiAlreadyUpdated, textEditorUpdated);
      }
    }

    public void setSelection(Location location, boolean uiAlreadyUpdated, boolean textEditorUpdated) {
      if (disposed) {
        return;
      }
      if (location == null) {
        return;
      }
      if (useServiceExtensionApi()) {
        final JsonObject params = new JsonObject();
        addLocationToParams(location, params);
        handleSetSelectionDaemon(invokeVmServiceExtension("setSelectionByLocation", params), uiAlreadyUpdated, textEditorUpdated);
      }
      // skip if the vm service is expected to be used directly.
    }

    /**
     * Helper when we need to set selection given an VM Service InstanceRef
     * instead of an InspectorInstanceRef.
     */
    public void setSelection(InstanceRef selection, boolean uiAlreadyUpdated, boolean textEditorUpdated) {
      // There is no excuse for calling setSelection using a disposed ObjectGroup.
      assert (!disposed);
      // This call requires the VM Service protocol as an VM Service InstanceRef is specified.
      handleSetSelectionVmService(invokeServiceMethodOnRefEval("setSelection", selection), uiAlreadyUpdated, textEditorUpdated);
    }

    private void handleSetSelectionVmService(CompletableFuture<InstanceRef> setSelectionResult,
                                             boolean uiAlreadyUpdated,
                                             boolean textEditorUpdated) {
      // TODO(jacobr): we need to cancel if another inspect request comes in while we are trying this one.
      skipIfDisposed(() -> setSelectionResult.thenAcceptAsync((InstanceRef instanceRef) -> skipIfDisposed(() -> {
        handleSetSelectionHelper("true".equals(instanceRef.getValueAsString()), uiAlreadyUpdated, textEditorUpdated);
      })));
    }

    private void handleSetSelectionHelper(boolean selectionChanged, boolean uiAlreadyUpdated, boolean textEditorUpdated) {
      if (selectionChanged) {
        notifySelectionChanged(uiAlreadyUpdated, textEditorUpdated);
      }
    }

    private void handleSetSelectionDaemon(CompletableFuture<JsonElement> setSelectionResult,
                                          boolean uiAlreadyUpdated,
                                          boolean textEditorUpdated) {
      skipIfDisposed(() ->
                       // TODO(jacobr): we need to cancel if another inspect request comes in while we are trying this one.
                       setSelectionResult.thenAcceptAsync(
                         (JsonElement json) -> skipIfDisposed(
                           () -> handleSetSelectionHelper(json.getAsBoolean(), uiAlreadyUpdated, textEditorUpdated)))
      );
    }

    public CompletableFuture<Map<String, InstanceRef>> getEnumPropertyValues(InspectorInstanceRef ref) {
      return nullIfDisposed(() -> {
        if (ref == null || ref.getId() == null) {
          return CompletableFuture.completedFuture(null);
        }
        return getInstance(toVmServiceInstanceRef(ref))
          .thenComposeAsync(
            (Instance instance) -> nullIfDisposed(() -> getInspectorLibrary().getClass(instance.getClassRef(), this).thenApplyAsync(
              (ClassObj clazz) -> nullValueIfDisposed(() -> {
                final Map<String, InstanceRef> properties = new LinkedHashMap<>();
                for (FieldRef field : clazz.getFields()) {
                  final String name = field.getName();
                  if (name.startsWith("_")) {
                    // Needed to filter out _deleted_enum_sentinel synthetic property.
                    // If showing private enum values is useful we could special case
                    // just the _deleted_enum_sentinel property name.
                    continue;
                  }
                  if (name.equals("values")) {
                    // Need to filter out the synthetic "values" member.
                    // TODO(jacobr): detect that this properties return type is
                    // different and filter that way.
                    continue;
                  }
                  if (field.isConst() && field.isStatic()) {
                    properties.put(field.getName(), field.getDeclaredType());
                  }
                }
                return properties;
              })
            )));
      });
    }

    public CompletableFuture<DiagnosticsNode> getDetailsSubtree(DiagnosticsNode node) {
      if (node == null) {
        return CompletableFuture.completedFuture(null);
      }
      return nullIfDisposed(() -> invokeServiceMethodReturningNode("getDetailsSubtree", node.getDartDiagnosticRef()));
    }

    public XDebuggerEditorsProvider getEditorsProvider() {
      return InspectorService.this.getDebugProcess().getEditorsProvider();
    }

    FlutterApp getApp() {
      return InspectorService.this.getApp();
    }

    /**
     * Await a Future invoking the callback on completion on the UI thread only if the
     * rhis ObjectGroup is still alive when the Future completes.
     */
    public <T> void safeWhenComplete(CompletableFuture<T> future, BiConsumer<? super T, ? super Throwable> action) {
      if (future == null) {
        return;
      }
      future.whenCompleteAsync(
        (T value, Throwable throwable) -> skipIfDisposed(() -> {
          ApplicationManager.getApplication().invokeLater(() -> {
            action.accept(value, throwable);
          });
        })
      );
    }

    public boolean isDisposed() {
      return disposed;
    }
  }

  public static String getFileUriPrefix() {
    return SystemInfo.isWindows ? "file:///" : "file://";
  }

  // TODO(jacobr): remove this method as soon as the
  // track-widget-creation kernel transformer is fixed to return paths instead
  // of URIs.
  public static String toSourceLocationUri(String path) {
    return getFileUriPrefix() + path;
  }

  public static String fromSourceLocationUri(String path, Project project) {
    final Workspace workspace = WorkspaceCache.getInstance(project).get();
    if (workspace != null) {
      path = workspace.convertPath(path);
    }

    final String filePrefix = getFileUriPrefix();
    return path.startsWith(filePrefix) ? path.substring(filePrefix.length()) : path;
  }

  public enum FlutterTreeType {
    widget("Widget"),
    renderObject("Render");
    // TODO(jacobr): add semantics, and layer trees.

    public final String displayName;

    FlutterTreeType(String displayName) {
      this.displayName = displayName;
    }
  }

  public interface InspectorServiceClient {
    void onInspectorSelectionChanged(boolean uiAlreadyUpdated, boolean textEditorUpdated);

    void onFlutterFrame();

    CompletableFuture<?> onForceRefresh();
  }
}
